/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, ChildFailed}
import akka.util.Timeout
import com.lightbend.kafkalagexporter.KafkaClient.KafkaClientContract
import com.lightbend.kafkalagexporter.watchers.Watcher

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object KafkaClusterManager {
  sealed trait Message
  sealed trait Stop extends Message
  final case object Stop extends Stop
  sealed trait Done extends Message
  final case object Done extends Done
  final case class ClusterAdded(c: KafkaCluster) extends Message
  final case class ClusterRemoved(c: KafkaCluster) extends Message

  private val stopTimeout: Timeout = 3.seconds

  def init(
            appConfig: AppConfig,
            metricsSink: () => MetricsSink,
            clientCreator: KafkaCluster => KafkaClientContract): Behavior[Message] = Behaviors.setup { context =>

    context.log.info("Starting Kafka Lag Exporter with configuration: \n{}", appConfig)

    if (appConfig.clusters.isEmpty && !appConfig.strimziWatcher)
      context.log.info("No watchers are defined and no clusters are statically configured.  Nothing to do.")

    val metricsSinkInst: MetricsSink = metricsSink()
    val watchers: Seq[ActorRef[Watcher.Message]] = Watcher.createClusterWatchers(context, appConfig)
    val reporter: ActorRef[MetricsSink.Message] = context.spawn(MetricsReporter.init(metricsSinkInst), "lag-reporter")
    appConfig.clusters.foreach(cluster => context.self ! ClusterAdded(cluster))

    context.watch(reporter)

    manager(appConfig, clientCreator, reporter, collectors = Map.empty, watchers)
  }

  def manager(
               appConfig: AppConfig,
               clientCreator: KafkaCluster => KafkaClientContract,
               reporter: ActorRef[MetricsSink.Message],
               collectors: Map[KafkaCluster, ActorRef[ConsumerGroupCollector.Message]],
               watchers: Seq[ActorRef[Watcher.Message]]): Behavior[Message] =
    Behaviors.receive[Message] {
      case (context, ClusterAdded(cluster)) =>
        context.log.info(s"Cluster Added: $cluster")

        val config = ConsumerGroupCollector.CollectorConfig(
          appConfig.pollInterval,
          appConfig.lookupTableSize,
          cluster
        )
        val collector = context.spawn(
          ConsumerGroupCollector.init(config, clientCreator, reporter),
          s"consumer-group-collector-${cluster.name}"
        )

        manager(appConfig, clientCreator, reporter, collectors + (cluster -> collector), watchers)

      case (context, ClusterRemoved(cluster)) =>
        context.log.info(s"Cluster Removed: $cluster")

        collectors.get(cluster) match {
          case Some(collector) =>
            collector ! ConsumerGroupCollector.Stop
            manager(appConfig, clientCreator, reporter, collectors - cluster, watchers)
          case None =>
            manager(appConfig, clientCreator, reporter, collectors, watchers)
        }

      case (context, _: Stop) =>
        context.log.info("Attempting graceful shutdown")
        watchers.foreach(_ ! Watcher.Stop)
        collectors.foreach { case (_, collector) => collector ! ConsumerGroupCollector.Stop }
        implicit val timeout = stopTimeout
        context.ask(reporter, (_: ActorRef[MetricsSink.Message]) => MetricsSink.Stop(context.self)) {
          case Success(_) => Done
          case Failure(ex) =>
            context.log.error("The metrics reporter shutdown failed.", ex)
            Done
        }
        Behaviors.same
      case (_, _: Done) =>
        Behaviors.stopped
    } receiveSignal {
      case (context, ChildFailed(`reporter`, cause)) =>
        context.log.error("The metrics reporter failed.  Shutting down.", cause)
        context.self ! Stop
        Behaviors.same
    }
}
