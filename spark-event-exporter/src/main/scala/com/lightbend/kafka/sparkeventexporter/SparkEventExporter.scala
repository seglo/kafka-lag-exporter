package com.lightbend.kafka.sparkeventexporter

import java.util.concurrent.Executors

import akka.actor.typed.{ActorSystem, Terminated}
import com.lightbend.kafka.kafkametricstools.{CodahaleMetricsSink, KafkaClient, MetricsSink, PrometheusEndpointSink}
import com.lightbend.kafka.sparkeventexporter.internal.{ExporterManager, Metrics}
import org.apache.spark.lightbend.sparkeventexporter.SparkEventExporterSource

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object SparkEventExporter {
  /**
   * Create a new SparkEventExporter instance with the provided configuration
   */
  def apply(config: Config): SparkEventExporter = new SparkEventExporter(config)
  def initialize(config: Config): SparkEventExporter = apply(config)
}

final class SparkEventExporter(config: Config) {
  import config._

  // Cached thread pool for various Kafka calls for non-blocking I/O
  private val kafkaClientEc = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  private val clientCreator = () => KafkaClient(cluster.bootstrapBrokers, clientGroupId)(kafkaClientEc)

  private val metricsSinkCreator = () => metricsSink match {
    case PrometheusEndpointSinkConfig(port) => PrometheusEndpointSink(port, Metrics.metricDefinitions)
    case SparkMetricsSinkConfig => createCodahaleMetricsSink
  }

  private val system = ActorSystem(ExporterManager.init(config, cluster, metricsSinkCreator, clientCreator), "spark-event-exporter")

  private def createCodahaleMetricsSink: MetricsSink = {
    val sparkEventSource = new SparkEventExporterSource()

    /*
     * Due to limitations with the dropwizard metrics library we must encode metric labels such as "topic" and "partition"
     * into the metric name itself, because labels/tags are naturally supported.  This requires us to register metrics
     * at runtime, but they're only added to Spark's internal metric registry when a metric `Source` is registered.
     * Therefore we have to re-register the whole set of metrics, in order to pick up new metrics that are created.  This
     * can be done by removing and registering the source again each time a new metric is added.
     *
     * This could be optimized by re-registering metrics based on a timer, but since an application's is likely only
     * going to generate new metrics at the beginning of its lifecycle it probably doesn't have much of a performance
     * impact.
     */
    val newMetricRegistered = () => {
      sparkEnv.metricsSystem.removeSource(sparkEventSource)
      sparkEnv.metricsSystem.registerSource(sparkEventSource)
    }

    CodahaleMetricsSink(
      sparkEventSource.metricRegistry,
      Metrics.metricDefinitions,
      newMetricRegistered
    )
  }

  /**
   * Synchronous call to stop the SparkEventExporter actor system
   */
  def stop(timeout: FiniteDuration = 5 seconds): Terminated = {
    system ! ExporterManager.Stop
    Await.result(system.whenTerminated, timeout)
  }
}
