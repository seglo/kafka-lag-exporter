package com.lightbend.kafka.kafkalagexporter

import java.util.concurrent.TimeUnit

import com.lightbend.kafka.core.{KafkaCluster, SimpleConfig}
import com.typesafe.config.Config
import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration

object AppConfig {
  def apply(config: Config): AppConfig = {
    val pollIntervalConfig = config.getDuration("poll-interval")
    val pollInterval = FiniteDuration(pollIntervalConfig.getSeconds, TimeUnit.SECONDS)
    val port = config.getInt("port")
    val clientGroupId = config.getString("client-group-id")
    val clusters = config.getConfigList("clusters").asScala.toList.map { clusterConfig =>
      KafkaCluster(
        clusterConfig.getString("name"),
        clusterConfig.getString("bootstrap-brokers")
      )
    }
    val strimziWatcher = config.getString("watchers.strimzi").toBoolean
    AppConfig(pollInterval, port, clientGroupId, clusters, strimziWatcher)
  }
}

final case class AppConfig(pollInterval: FiniteDuration, port: Int, clientGroupId: String, clusters: List[KafkaCluster],
                           strimziWatcher: Boolean) extends SimpleConfig {
  override def toString(): String = {
    val clusterString =
      if (clusters.isEmpty)
        "  (none)"
      else
        clusters.map { cluster =>
          s"""
             |  Cluster name: ${cluster.name}
             |  Cluster Kafka bootstrap brokers: ${cluster.bootstrapBrokers}
           """.stripMargin
        }.mkString("\n")
    s"""
       |Poll interval: $pollInterval
       |Prometheus metrics endpoint port: $port
       |Admin client consumer group id: $clientGroupId
       |Statically defined Clusters:
       |$clusterString
       |Watchers:
       |  Strimzi: $strimziWatcher
     """.stripMargin
  }
}

