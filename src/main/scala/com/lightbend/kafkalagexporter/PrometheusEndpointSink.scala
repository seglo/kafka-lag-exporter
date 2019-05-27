/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import com.lightbend.kafkalagexporter.MetricsSink._
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.{CollectorRegistry, Gauge}

import scala.util.Try

object PrometheusEndpointSink {
  def apply(httpPort: Int, definitions: MetricDefinitions): MetricsSink =
    Try(new PrometheusEndpointSink(httpPort, definitions))
      .fold(t => throw new Exception("Could not create Prometheus Endpoint", t), sink => sink)
}

class PrometheusEndpointSink private(httpPort: Int, definitions: MetricDefinitions) extends MetricsSink {

  private val server = new HTTPServer(httpPort)
  private val registry = CollectorRegistry.defaultRegistry

  DefaultExports.initialize()

  private val metrics: Map[GaugeDefinition, Gauge] = definitions.map(definition =>
    definition -> Gauge.build()
      .name(definition.name)
      .help(definition.help)
      .labelNames(definition.labels: _*)
      .register(registry)
  ).toMap

  override def report(m: MetricValue): Unit = {
    val metric = metrics.getOrElse(m.definition, throw new IllegalArgumentException(s"No metric with definition ${m.definition.name} registered"))
    metric.labels(m.labels: _*).set(m.value)
  }


  override def remove(m: RemoveMetric): Unit =
    metrics.get(m.definition).foreach(_.remove(m.labels: _*))

  override def stop(): Unit = {
    /*
     * Unregister all collectors (i.e. Gauges).  Useful for integration tests.
     * NOTE: This will nuke all JVM metrics too, but we don't care about those in tests.
     */
    registry.clear()
    server.stop()
  }
}
