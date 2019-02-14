package com.lightbend.kafka.sparkeventexporter

import java.util.concurrent.Executors

import akka.actor.typed.ActorSystem
import com.lightbend.kafka.kafkametricstools.{CodahaleMetricsSink, KafkaClient, PrometheusEndpointSink}
import com.lightbend.kafka.sparkeventexporter.internal.{ExporterManager, Metrics}
import org.apache.spark.SparkEnv
import org.apache.spark.lightbend.sparkeventexporter.SparkEventExporterSource
import org.apache.spark.metrics.MetricsSystem

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

  // TODO: test move into metricsSinkCreator
  val sparkEventSource = new SparkEventExporterSource()
  // TODO: omit or call newMetricRegistered
  sparkEnv.metricsSystem.registerSource(sparkEventSource)

//  sparkEventSource.testTwo()
//  sparkEnv.metricsSystem.removeSource(sparkEventSource)
//  sparkEnv.metricsSystem.registerSource(sparkEventSource)

  private val newMetricRegistered = () => {
    sparkEnv.metricsSystem.removeSource(sparkEventSource)
    sparkEnv.metricsSystem.registerSource(sparkEventSource)
  }
  val sink = CodahaleMetricsSink(sparkEventSource.metricRegistry, Metrics.metricDefinitions, newMetricRegistered)
//  sparkEnv.metricsSystem.removeSource(sparkEventSource)
//  sparkEnv.metricsSystem.registerSource(sparkEventSource)

  private val metricsSinkCreator = () => metricsSink match {
    case PrometheusEndpointSinkConfig(port) => PrometheusEndpointSink(port, Metrics.metricDefinitions)
    case SparkMetricsSinkConfig => sink
  }

  private val system = ActorSystem(ExporterManager.init(config, cluster, metricsSinkCreator, clientCreator), "spark-event-exporter")

  // Add shutdown hook to respond to SIGTERM and gracefully shutdown the actor system
  sys.ShutdownHookThread {
    system ! ExporterManager.Stop
    Await.result(system.whenTerminated, 5 seconds)
  }
}
