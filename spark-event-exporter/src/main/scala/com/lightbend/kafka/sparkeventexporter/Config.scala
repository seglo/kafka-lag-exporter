package com.lightbend.kafka.sparkeventexporter
import java.util.UUID

import com.lightbend.kafka.sparkeventexporter.Config.KafkaCluster
import org.apache.spark.sql.SparkSession

object Config {
  type KafkaCluster = com.lightbend.kafka.core.KafkaCluster
}

final case class Config(
                         cluster: KafkaCluster,
                         providedName: String,
                         sparkSession: SparkSession,
                         port: Int = 8080,
                         clientGroupId: String = s"spark-event-exporter-${UUID.randomUUID()}"
                       ) {
  require(cluster.bootstrapBrokers != null && cluster.bootstrapBrokers != "",
    "You must provide the Kafka bootstrap brokers connection string")
  require(sparkSession != null, "You must provide a SparkSession object")

  override def toString: String = {
    s"""
       |Kafka Cluster:
       |  Name: ${cluster.name}
       |  Bootstrap Brokers: ${cluster.bootstrapBrokers}
       |Provided name: $providedName
       |Prometheus metrics endpoint port: $port
       |Client consumer group id: $clientGroupId
     """.stripMargin
  }
}
