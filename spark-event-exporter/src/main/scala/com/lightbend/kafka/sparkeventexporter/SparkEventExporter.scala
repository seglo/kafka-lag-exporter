package com.lightbend.kafka.sparkeventexporter

import java.util.concurrent.Executors

import akka.actor.typed.ActorSystem
import com.lightbend.kafka.core.{KafkaClient, PrometheusEndpoint}
import com.lightbend.kafka.sparkeventexporter.internal.{ExporterManager, Metrics}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object SparkEventExporter {
  def apply(config: Config): SparkEventExporter =
    new SparkEventExporter(config)
}

final class SparkEventExporter(config: Config) {
  import config._

  // Cached thread pool for various Kafka calls for non-blocking I/O
  private val kafkaClientEc = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  private val clientCreator = () => KafkaClient(cluster.bootstrapBrokers, clientGroupId)(kafkaClientEc)
  private val endpointCreator = () => PrometheusEndpoint(port, Metrics.metricDefinitions)

  private val system = ActorSystem(ExporterManager.init(config, cluster, endpointCreator, clientCreator), "spark-event-exporter")

  // Add shutdown hook to respond to SIGTERM and gracefully shutdown the actor system
  sys.ShutdownHookThread {
    system ! ExporterManager.Stop
    Await.result(system.whenTerminated, 5 seconds)
  }
}
