package com.lightbend.kafka.kafkalagexporter

import com.lightbend.kafka.kafkametricstools.{KafkaCluster, SimpleConfig}
import com.typesafe.config.Config

import scala.collection.JavaConverters._
import scala.compat.java8.DurationConverters._
import scala.concurrent.duration.FiniteDuration

object AppConfig {
  def apply(config: Config): AppConfig = {
    val c = config.getConfig("kafka-lag-exporter")
    val pollInterval = c.getDuration("poll-interval").toScala
    val lookupTableSize = c.getInt("lookup-table-size")
    val port = c.getInt("port")
    val clientGroupId = c.getString("client-group-id")
    val kafkaClientTimeout = c.getDuration("kafka-client-timeout").toScala
    val clusters = c.getConfigList("clusters").asScala.toList.map { clusterConfig =>
      KafkaCluster(
        clusterConfig.getString("name"),
        clusterConfig.getString("bootstrap-brokers")
      )
    }
    val strimziWatcher = c.getString("watchers.strimzi").toBoolean
    AppConfig(pollInterval, lookupTableSize, port, clientGroupId, kafkaClientTimeout, clusters, strimziWatcher)
  }
}

final case class AppConfig(pollInterval: FiniteDuration, lookupTableSize: Int, port: Int, clientGroupId: String,
                           clientTimeout: FiniteDuration, clusters: List[KafkaCluster], strimziWatcher: Boolean)
  extends SimpleConfig {
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
       |Lookup table size: $lookupTableSize
       |Prometheus metrics endpoint port: $port
       |Admin client consumer group id: $clientGroupId
       |Kafka client timeout: $clientTimeout
       |Statically defined Clusters:
       |$clusterString
       |Watchers:
       |  Strimzi: $strimziWatcher
     """.stripMargin
  }
}

