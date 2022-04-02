/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2022 Sean Glover <https://seanglover.com>
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
  final case class NamedCreator(name: String, creator: () => MetricsSink)


  private val stopTimeout: Timeout = 3.seconds

  def init(
            appConfig: AppConfig,
            metricsSinks: List[NamedCreator],
            clientCreator: KafkaCluster => KafkaClientContract): Behavior[Message] = Behaviors.setup { context =>

    context.log.info("Starting Kafka Lag Exporter with configuration: \n{}", appConfig)

    if (appConfig.clusters.isEmpty && !appConfig.strimziWatcher)
      context.log.info("No watchers are defined and no clusters are statically configured.  Nothing to do.")

    val watchers: Seq[ActorRef[Watcher.Message]] = Watcher.createClusterWatchers(context, appConfig)
    val reporters: List[ActorRef[MetricsSink.Message]] = metricsSinks.map { metricsSink : NamedCreator =>
      context.spawn(MetricsReporter.init(metricsSink.creator()), metricsSink.name)
    }
    appConfig.clusters.foreach(cluster => context.self ! ClusterAdded(cluster))

    reporters.map { context.watch }

    manager(appConfig, clientCreator, reporters, collectors = Map.empty, watchers)
  }

  def manager(
               appConfig: AppConfig,
               clientCreator: KafkaCluster => KafkaClientContract,
               reporters: List[ActorRef[MetricsSink.Message]],
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
          ConsumerGroupCollector.init(config, clientCreator, reporters),
          s"consumer-group-collector-${cluster.name}"
        )

        manager(appConfig, clientCreator, reporters, collectors + (cluster -> collector), watchers)

      case (context, ClusterRemoved(cluster)) =>
        context.log.info(s"Cluster Removed: $cluster")

        collectors.get(cluster) match {
          case Some(collector) =>
            collector ! ConsumerGroupCollector.Stop
            manager(appConfig, clientCreator, reporters, collectors - cluster, watchers)
          case None =>
            manager(appConfig, clientCreator, reporters, collectors, watchers)
        }

      case (context, _: Stop) =>
        context.log.info("Attempting graceful shutdown")
        watchers.foreach(_ ! Watcher.Stop)
        collectors.foreach { case (_, collector) => collector ! ConsumerGroupCollector.Stop }
        implicit val timeout = stopTimeout
        reporters.foreach { reporter =>
          context.ask(reporter, (_: ActorRef[MetricsSink.Message]) => MetricsSink.Stop(context.self)) {
            case Success(_) => Done
            case Failure(ex) =>
              context.log.error("The metrics reporter shutdown failed.", ex)
              Done
          }
        }
        Behaviors.same
      case (_, _: Done) =>
        Behaviors.stopped
    } receiveSignal {
      case (context, ChildFailed(`reporters`, cause)) =>
        context.log.error("The metrics reporter failed.  Shutting down.", cause)
        context.self ! Stop
        Behaviors.same
    }
}
