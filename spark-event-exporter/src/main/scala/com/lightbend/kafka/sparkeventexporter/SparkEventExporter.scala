package com.lightbend.kafka.sparkeventexporter

import java.util.concurrent.Executors

import akka.actor.typed.ActorSystem
import com.lightbend.kafka.core.{KafkaClient, KafkaCluster, PrometheusEndpoint}
import com.typesafe.config.ConfigFactory
import org.apache.spark.sql.SparkSession

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object SparkEventExporter {
  def apply(session: SparkSession, kafkaBootstrapServers: String): SparkEventExporter =
    new SparkEventExporter(session, kafkaBootstrapServers)
}

class SparkEventExporter(session: SparkSession, kafkaBootstrapServers: String) {
  require(kafkaBootstrapServers != null && kafkaBootstrapServers != "", "You must add 'kafka.bootstrap.servers' to your Spark session")

  // Cached thread pool for various Kafka calls for non-blocking I/O
  private val kafkaClientEc = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  private val appConfig = AppConfig(ConfigFactory.load().getConfig("spark-event-exporter"))
  private val cluster = KafkaCluster(kafkaBootstrapServers, kafkaBootstrapServers)

  private val clientCreator = () => KafkaClient(kafkaBootstrapServers, appConfig.clientGroupId)(kafkaClientEc)
  private val endpointCreator = () => PrometheusEndpoint(appConfig.port, Metrics.metricDefinitions)

  private val system = ActorSystem(ExporterManager.init(appConfig, cluster, endpointCreator, clientCreator, session), "sparkeventexporter")

  // Add shutdown hook to respond to SIGTERM and gracefully shutdown the actor system
  sys.ShutdownHookThread {
    system ! ExporterManager.Stop
    Await.result(system.whenTerminated, 5 seconds)
  }
}
