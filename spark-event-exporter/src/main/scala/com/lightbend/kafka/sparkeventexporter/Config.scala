package com.lightbend.kafka.sparkeventexporter
import java.util.UUID

import com.lightbend.kafka.kafkametricstools.KafkaCluster
import org.apache.spark.SparkEnv
import org.apache.spark.sql.SparkSession
import scala.concurrent.duration._

sealed trait MetricsSinkConfig

/**
 * Exposes an internal prometheus HTTP metrics endpoint
 */
final case class PrometheusEndpointSinkConfig(port: Int = 8080) extends MetricsSinkConfig

/**
 * Uses Spark's existing metrics system.  This will result in a lack of fidelity in terms of the number of labels/tags
 * that can be expressed per metric.
 */
case object SparkMetricsSinkConfig extends MetricsSinkConfig

final case class Config(
                         cluster: KafkaCluster,
                         providedName: String,
                         sparkSession: SparkSession,
                         sparkEnv: SparkEnv,
                         metricsSink: MetricsSinkConfig,
                         kafkaClientTimeout: FiniteDuration = 10 seconds,
                         clientGroupId: String = s"spark-event-exporter-${UUID.randomUUID()}"
                       ) {
  require(cluster.bootstrapBrokers != null && cluster.bootstrapBrokers != "",
    "You must provide the Kafka bootstrap brokers connection string")
  require(sparkSession != null, "You must provide a SparkSession object")

  override def toString: String = {
    s"""
       |Kafka cluster:
       |  Name: ${cluster.name}
       |  Bootstrap brokers: ${cluster.bootstrapBrokers}
       |Provided name: $providedName
       |Metrics sink: $metricsSink
       |Kafka client timeout ms: ${kafkaClientTimeout.toMillis}
       |Client consumer group id: $clientGroupId
     """.stripMargin
  }
}
