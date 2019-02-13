package com.lightbend.kafka.kafkametricstools

import com.lightbend.kafka.kafkametricstools.PrometheusEndpoint.{Metric, MetricDefinitions, PrometheusMetricsEndpointContract}
import io.prometheus.client.Gauge
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports

object PrometheusEndpoint {
  trait Message
  sealed trait Stop extends Message
  final case object Stop extends Stop

  type MetricDefinitions = Map[Class[_], GaugeDefinition]

  trait Metric {
    def value: Double
    def labels: List[String]
  }

  final case class GaugeDefinition(name: String, help: String, label: String*)

  def apply(httpPort: Int, metricsDefinitions: MetricDefinitions): PrometheusMetricsEndpointContract =
    new PrometheusEndpoint(httpPort, metricsDefinitions)

  trait PrometheusMetricsEndpointContract {
    def report(m: Metric): Unit
    def stop(): Unit
  }
}

class PrometheusEndpoint private(httpPort: Int, metricsDefinitions: MetricDefinitions) extends
  PrometheusMetricsEndpointContract {

  private val server = new HTTPServer(httpPort)

  DefaultExports.initialize()

  private val metrics: Map[Class[_], Gauge] = metricsDefinitions.map { case (key, defn) =>
    println("evaluating map again")
    key -> Gauge.build()
      .name(defn.name)
      .help(defn.help)
      .labelNames(defn.label: _*)
      .register()
  }.toMap

  println(s"Registered metrics: $metrics")

  override def report(m: Metric): Unit = {
    val metric = metrics.getOrElse(m.getClass, throw new Exception(s"No metric with type ${m.getClass.getName} defined"))
    metric.labels(m.labels: _*).set(m.value)
  }
  override def stop(): Unit = server.stop()
}
