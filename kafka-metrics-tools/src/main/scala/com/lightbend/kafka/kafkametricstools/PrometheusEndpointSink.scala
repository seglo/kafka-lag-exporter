package com.lightbend.kafka.kafkametricstools
import com.lightbend.kafka.kafkametricstools.MetricsSink.{Metric, MetricDefinitions}
import io.prometheus.client.Gauge
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports

import scala.util.Try

object PrometheusEndpointSink {
  def apply(httpPort: Int, metricsDefinitions: MetricDefinitions): MetricsSink =
    Try(new PrometheusEndpointSink(httpPort, metricsDefinitions))
      .fold(t => throw new Exception("Could not create Prometheus Endpoint", t), sink => sink)
}

class PrometheusEndpointSink private(httpPort: Int, definitions: MetricDefinitions) extends MetricsSink {

  private val server = new HTTPServer(httpPort)

  DefaultExports.initialize()

  private val metrics: Map[Class[_], Gauge] = definitions.map { case (key, defn) =>
    key -> Gauge.build()
      .name(defn.name)
      .help(defn.help)
      .labelNames(defn.label: _*)
      .register()
  }.toMap

  override def report(m: Metric): Unit = {
    val metric = metrics.getOrElse(m.getClass, throw new IllegalArgumentException(s"No metric with type ${m.getClass.getName} defined"))
    metric.labels(m.labels: _*).set(m.value)
  }
  override def stop(): Unit = server.stop()
}
