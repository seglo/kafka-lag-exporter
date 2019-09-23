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
  type ClusterName = String
  type GlobalLabels = Map[String, String]
  type ClusterGlobalLabels = Map[ClusterName, GlobalLabels]

  def apply(definitions: MetricDefinitions, metricWhitelist: List[String], clusterGlobalLabels: ClusterGlobalLabels,
            server: HTTPServer, registry: CollectorRegistry): MetricsSink = {
    Try(new PrometheusEndpointSink(definitions, metricWhitelist, server, registry))
      .fold(t => throw new Exception("Could not create Prometheus Endpoint", t), sink => sink)
  }
}

class PrometheusEndpointSink private(definitions: MetricDefinitions, metricWhitelist: List[String],
                                     server: HTTPServer, registry: CollectorRegistry) extends MetricsSink {

  DefaultExports.initialize()

  private val metrics: Map[GaugeDefinition, Gauge] = register()

  private def register(): Map[GaugeDefinition, Gauge] = {
    definitions
      .filter(d => metricWhitelist.exists(d.name.matches))
      .map { d =>
        d -> Gauge
          .build()
          .name(d.name)
          .help(d.help)
          .labelNames(d.labels: _*)
          .register(registry)
      }
      .toMap
  }

  override def report(m: MetricValue): Unit = {
    if(metricWhitelist.exists(m.definition.name.matches)) {
      val metric = metrics.getOrElse(m.definition, throw new IllegalArgumentException(s"No metric with definition ${m.definition.name} registered"))
      metric.labels(m.labels: _*).set(m.value)
    }
  }

  override def remove(m: RemoveMetric): Unit = {
    if(metricWhitelist.exists(m.definition.name.matches)) {
      metrics.get(m.definition).foreach(_.remove(m.labels: _*))
    }
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
