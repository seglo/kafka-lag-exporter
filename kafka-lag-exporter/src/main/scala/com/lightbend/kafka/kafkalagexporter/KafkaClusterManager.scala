package com.lightbend.kafka.kafkalagexporter

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import com.lightbend.kafka.kafkalagexporter.watchers.Watcher
import com.lightbend.kafka.kafkametricstools.KafkaClient.KafkaClientContract
import com.lightbend.kafka.kafkametricstools.{KafkaCluster, MetricsReporter, MetricsSink}

object KafkaClusterManager {
  sealed trait Message
  sealed trait Stop extends Message
  final case object Stop extends Stop
  final case class ClusterAdded(c: KafkaCluster) extends Message
  final case class ClusterRemoved(c: KafkaCluster) extends Message

  def init(
            appConfig: AppConfig,
            metricsSink: () => MetricsSink,
            clientCreator: String => KafkaClientContract): Behavior[Message] =
    Behaviors.setup { context =>
      context.log.info("Starting Kafka Lag Exporter with configuration: \n{}", appConfig)

      val watchers: Seq[ActorRef[Watcher.Message]] = Watcher.createClusterWatchers(context, appConfig)
      val reporter: ActorRef[MetricsSink.Message] = context.spawn(MetricsReporter.init(metricsSink), "lag-reporter")
      appConfig.clusters.foreach(cluster => context.self ! ClusterAdded(cluster))

      manager(appConfig, metricsSink, clientCreator, reporter, collectors = Map.empty, watchers)
    }

  def manager(
               appConfig: AppConfig,
               metricsSink: () => MetricsSink,
               clientCreator: String => KafkaClientContract,
               reporter: ActorRef[MetricsSink.Message],
               collectors: Map[KafkaCluster, ActorRef[ConsumerGroupCollector.Message]],
               watchers: Seq[ActorRef[Watcher.Message]]): Behaviors.Receive[Message] =
    Behaviors.receive[Message] {
      case (context, ClusterAdded(cluster)) =>
        context.log.info(s"Cluster Added: $cluster")

        val config = ConsumerGroupCollector.CollectorConfig(appConfig.pollInterval, cluster.name, cluster.bootstrapBrokers)
        val collector = context.spawn(ConsumerGroupCollector.init(config, clientCreator, reporter), s"consumer-group-collector-${cluster.name}")
        collector ! ConsumerGroupCollector.Collect

        manager(appConfig, metricsSink, clientCreator, reporter, collectors + (cluster -> collector), watchers)

      case (context, ClusterRemoved(cluster)) =>
        context.log.info(s"Cluster Removed: $cluster")

        collectors.get(cluster) match {
          case Some(collector) =>
            collector ! ConsumerGroupCollector.Stop
            manager(appConfig, metricsSink, clientCreator, reporter, collectors - cluster, watchers)
          case None =>
            manager(appConfig, metricsSink, clientCreator, reporter, collectors, watchers)
        }

      case (context, _: Stop) =>
        context.log.info("Attempting graceful shutdown")
        watchers.foreach(_ ! Watcher.Stop)
        collectors.foreach { case (_, collector) => collector ! ConsumerGroupCollector.Stop }
        reporter ! MetricsSink.Stop
        Behaviors.stopped
    }
}


