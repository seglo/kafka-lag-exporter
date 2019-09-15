/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import com.lightbend.kafkalagexporter.MetricsSink._
import com.lightbend.kafkalagexporter.PrometheusEndpointSink.ClusterGlobalLabels
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
    Try(new PrometheusEndpointSink(definitions, metricWhitelist, clusterGlobalLabels, server, registry))
      .fold(t => throw new Exception("Could not create Prometheus Endpoint", t), sink => sink)
  }
}

class PrometheusEndpointSink private(definitions: MetricDefinitions, metricWhitelist: List[String], clusterGlobalLabels: ClusterGlobalLabels,
                                     server: HTTPServer, registry: CollectorRegistry) extends MetricsSink {
  DefaultExports.initialize()

  private val metrics: Map[PrometheusEndpointSink.ClusterName, Map[GaugeDefinition, Gauge]] = clusterGlobalLabels.map {
    case (clusterName, globalLabels) =>
      clusterName -> definitions.filter(d => metricWhitelist.exists(d.name.matches)).map { d =>
        d -> Gauge.build()
          .name(d.name)
          .help(d.help)
          .labelNames(globalLabels.keys.toSeq ++ d.labels: _*)
          .register(registry)
      }.toMap
  }

  override def report(m: MetricValue): Unit = {
    if(metricWhitelist.exists(m.definition.name.matches)) {
      val metric = getMetricsForClusterName(m.definition, m.clusterName)
      val globalLabelValuesForCluster = clusterGlobalLabels.getOrElse(m.clusterName, Map.empty)
      metric.labels(globalLabelValuesForCluster.values.toSeq ++ m.labels: _*).set(m.value)
    }
  }

  override def remove(m: RemoveMetric): Unit = {
    if(metricWhitelist.exists(m.definition.name.matches)) {
      metrics.foreach { case (_, gaugeDefinitionsForCluster) =>
        val globalLabelValuesForCluster = clusterGlobalLabels.getOrElse(m.clusterName, Map.empty)
        gaugeDefinitionsForCluster.get(m.definition).foreach(_.remove(globalLabelValuesForCluster.values.toSeq ++ m.labels: _*))
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

  private def getMetricsForClusterName(gaugeDefinition: GaugeDefinition, clusterName: String): Gauge = {
    val metricsForCluster = metrics.getOrElse(clusterName, throw new IllegalArgumentException(s"No metric for the $clusterName registered"))
    metricsForCluster.getOrElse(gaugeDefinition, throw new IllegalArgumentException(s"No metric with definition ${gaugeDefinition.name} registered"))
  }
}
