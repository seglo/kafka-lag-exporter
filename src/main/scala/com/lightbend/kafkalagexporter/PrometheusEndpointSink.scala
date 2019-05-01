/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import com.lightbend.kafkalagexporter.MetricsSink.{Metric, MetricDefinitions}
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.{CollectorRegistry, Gauge}

import scala.util.Try

object PrometheusEndpointSink {
  def apply(httpPort: Int, metricsDefinitions: MetricDefinitions): MetricsSink =
    Try(new PrometheusEndpointSink(httpPort, metricsDefinitions))
      .fold(t => throw new Exception("Could not create Prometheus Endpoint", t), sink => sink)
}

class PrometheusEndpointSink private(httpPort: Int, definitions: MetricDefinitions) extends MetricsSink {

  private val server = new HTTPServer(httpPort)
  private val registry = CollectorRegistry.defaultRegistry

  DefaultExports.initialize()

  private val metrics: Map[Class[_], Gauge] = definitions.map { case (key, defn) =>
    key -> Gauge.build()
      .name(defn.name)
      .help(defn.help)
      .labelNames(defn.label: _*)
      .register(registry)
  }.toMap

  override def report(m: Metric): Unit = {
    val metric = metrics.getOrElse(m.getClass, throw new IllegalArgumentException(s"No metric with type ${m.getClass.getName} defined"))
    metric.labels(m.labels: _*).set(m.value)
  }
  override def stop(): Unit = {
    /*
     * Unregister all collectors (i.e. Gauges).  Useful for integration tests.
     * NOTE: This will nuke all JVM metrics too, but we don't care about those in tests.
     */
    registry.clear()
    server.stop()
  }
}
