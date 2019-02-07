package com.lightbend.sparkeventsexporter
import java.util.concurrent.Executors

import akka.actor.typed.ActorSystem
import com.lightbend.kafkaclientmetrics.{KafkaClient, KafkaCluster, PrometheusEndpoint}
import com.typesafe.config.ConfigFactory
import org.apache.spark.sql.SparkSession

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object SparkEventsExporter {
  def apply(session: SparkSession): SparkEventsExporter = new SparkEventsExporter(session)
}

class SparkEventsExporter(session: SparkSession) {
  private val bootstrapServers = session.sessionState.conf.getConfString("kafka.bootstrap.servers")
  require(bootstrapServers != null && bootstrapServers != "", "You must add 'kafka.bootstrap.servers' to your Spark session")

  // Cached thread pool for various Kafka calls for non-blocking I/O
  private val kafkaClientEc = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  private val appConfig = AppConfig(ConfigFactory.load().getConfig("spark-events-exporter"))
  private val cluster = KafkaCluster(bootstrapServers, bootstrapServers)

  private val clientCreator = () => KafkaClient(bootstrapServers, appConfig.clientGroupId)(kafkaClientEc)
  private val endpointCreator = () => PrometheusEndpoint(appConfig.port, Metrics.metricDefinitions)

  private val system = ActorSystem(ExporterManager.init(appConfig, cluster, endpointCreator, clientCreator, session), "sparkeventsexporterlib")

  // Add shutdown hook to respond to SIGTERM and gracefully shutdown the actor system
  sys.ShutdownHookThread {
    system ! ExporterManager.Stop
    Await.result(system.whenTerminated, 5 seconds)
  }
}
