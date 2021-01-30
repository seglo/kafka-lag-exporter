/*
 * Copyright (C) 2019-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import com.lightbend.kafkalagexporter.MetricsSink._
import com.lightbend.kafkalagexporter.EndpointSink.ClusterGlobalLabels
import com.lightbend.kafkalagexporter.PrometheusEndpointSink.Metrics
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.{CollectorRegistry, Gauge}

import scala.util.Try

object PrometheusEndpointSink {
  type Metrics = Map[GaugeDefinition, Gauge]

  def apply(sinkConfig: PrometheusEndpointSinkConfig, definitions: MetricDefinitions, clusterGlobalLabels: ClusterGlobalLabels, registry: CollectorRegistry): MetricsSink = {
    Try(new PrometheusEndpointSink(sinkConfig: PrometheusEndpointSinkConfig, definitions, clusterGlobalLabels, new HTTPServer(sinkConfig.port), registry))
      .fold(t => throw new Exception("Could not create Prometheus Endpoint", t), sink => sink)
  }

  def apply(sinkConfig: PrometheusEndpointSinkConfig, definitions: MetricDefinitions, clusterGlobalLabels: ClusterGlobalLabels, server: HTTPServer, registry: CollectorRegistry): MetricsSink = {
    Try(new PrometheusEndpointSink(sinkConfig: PrometheusEndpointSinkConfig, definitions, clusterGlobalLabels, server, registry))
      .fold(t => throw new Exception("Could not create Prometheus Endpoint", t), sink => sink)
  }
}

class PrometheusEndpointSink private(sinkConfig: PrometheusEndpointSinkConfig, definitions: MetricDefinitions, clusterGlobalLabels: ClusterGlobalLabels,
                                     server: HTTPServer, registry: CollectorRegistry) extends EndpointSink(clusterGlobalLabels) {
  DefaultExports.initialize()

  private val metrics: Metrics = {
    definitions.filter(d => sinkConfig.metricWhitelist.exists(d.name.matches)).map { d =>
      d -> Gauge.build()
        .name(d.name)
        .help(d.help)
        .labelNames(globalLabelNames ++ d.labels: _*)
        .register(registry)
    }.toMap
   }

  override def report(m: MetricValue): Unit = {
    if (sinkConfig.metricWhitelist.exists(m.definition.name.matches)) {
      val metric = metrics.getOrElse(m.definition, throw new IllegalArgumentException(s"No metric with definition ${m.definition.name} registered"))
      metric.labels(getGlobalLabelValuesOrDefault(m.clusterName) ++ m.labels: _*).set(m.value)
    }
  }

  override def remove(m: RemoveMetric): Unit = {
    if (sinkConfig.metricWhitelist.exists(m.definition.name.matches)) {
      for {
        gauge <- metrics.get(m.definition)
      } {
        val metricLabels = getGlobalLabelValuesOrDefault(m.clusterName) ++ m.labels
        gauge.remove(metricLabels: _*)
      }
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
