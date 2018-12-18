package com.lightbend.kafkalagexporter

import java.util.concurrent.Executors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import com.lightbend.kafkalagexporter.AppConfig.Cluster
import com.lightbend.kafkalagexporter.watchers.Watcher
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object MainApp extends App {
  sealed trait Message
  sealed trait Stop extends Message
  final case object Stop extends Stop
  final case class ClusterAdded(c: Cluster) extends Message
  final case class ClusterRemoved(c: Cluster) extends Message

  // Cached thread pool for various Kafka calls for non-blocking I/O
  val kafkaClientEc = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  val appConfig = AppConfig(ConfigFactory.load().getConfig("kafka-lag-exporter"))

  val clientCreator = (bootstrapBrokers: String) => KafkaClient(bootstrapBrokers, appConfig.clientGroupId)(kafkaClientEc)
  val endpointCreator = () => PrometheusMetricsEndpoint(appConfig.port)

  val mainSetup: Behavior[Message] =
    Behaviors.setup { context =>
      context.log.info("Starting Kafka Lag Exporter with configuration: \n{}", appConfig)

      val watchers: Seq[ActorRef[Watcher.Message]] = Watcher.createClusterWatchers(context, appConfig)
      val reporter: ActorRef[LagReporter.Message] = context.spawn(LagReporter.init(appConfig, endpointCreator), "lag-reporter")
      appConfig.clusters.foreach(cluster => context.self ! ClusterAdded(cluster))

      main(reporter, collectors = Map.empty, watchers)
    }

  def main(reporter: ActorRef[LagReporter.Message],
           collectors: Map[Cluster, ActorRef[ConsumerGroupCollector.Message]],
           watchers: Seq[ActorRef[Watcher.Message]]): Behaviors.Receive[Message] = Behaviors.receive[MainApp.Message] {

    case (context, ClusterAdded(cluster)) =>
      context.log.info(s"Cluster Added: $cluster")

      val config = ConsumerGroupCollector.CollectorConfig(appConfig.pollInterval, cluster.name, cluster.bootstrapBrokers)
      val collector = context.spawn(ConsumerGroupCollector.init(config, clientCreator, reporter), s"consumer-group-collector-${cluster.name}")
      collector ! ConsumerGroupCollector.Collect

      main(reporter, collectors + (cluster -> collector), watchers)

    case (context, ClusterRemoved(cluster)) =>
      context.log.info(s"Cluster Removed: $cluster")

      collectors.get(cluster) match {
        case Some(collector) =>
          collector ! ConsumerGroupCollector.Stop
          main(reporter, collectors - cluster, watchers)
        case None =>
          main(reporter, collectors, watchers)
      }

    case (context, _: Stop) =>
      context.log.info("Attempting graceful shutdown")
      watchers.foreach(_ ! Watcher.Stop)
      collectors.foreach { case (_, collector) => collector ! ConsumerGroupCollector.Stop }
      reporter ! LagReporter.Stop
      Behaviors.stopped
  }

  val system = ActorSystem(mainSetup, "kafkalagexporterapp")

  // Add shutdown hook to respond to SIGTERM and gracefully shutdown the actor system
  sys.ShutdownHookThread {
    system ! Stop
    Await.result(system.whenTerminated, 5 seconds)
  }
}
