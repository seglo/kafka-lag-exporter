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
  def apply(appConfig: AppConfig, definitions: MetricDefinitions): MetricsSink =
    Try(new PrometheusEndpointSink(appConfig, definitions))
      .fold(t => throw new Exception("Could not create Prometheus Endpoint", t), sink => sink)
}

class PrometheusEndpointSink private(appConfig: AppConfig, definitions: MetricDefinitions) extends MetricsSink {

  private val server = new HTTPServer(appConfig.port)
  private val registry = CollectorRegistry.defaultRegistry

  DefaultExports.initialize()

  private val metrics: Map[String, Map[GaugeDefinition, Gauge]] = {
    appConfig.clusters.map { cluster =>
      val globalLabelNamesForCluster = appConfig.globalLabelsForCluster(cluster.name).keys.toSeq
      cluster.name -> definitions.map(definition =>
        definition -> Gauge.build()
          .name(definition.name)
          .help(definition.help)
          .labelNames(globalLabelNamesForCluster ++ definition.labels: _*)
          .register(registry)
      ).toMap
    }.toMap
  }

  override def report(m: MetricValue): Unit = {
    val metric = getMetricsForClusterName(m.definition, m.clusterName)
    val globalLabelValuesForCluster = appConfig.globalLabelsForCluster(m.clusterName).values.toSeq
    metric.labels(globalLabelValuesForCluster ++ m.labels: _*).set(m.value)
  }


  override def remove(m: RemoveMetric): Unit = {
    metrics.foreach { case (_, gaugeDefinitionsForCluster) =>
      gaugeDefinitionsForCluster.get(m.definition).foreach(_.remove(m.labels: _*))
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
    val metricsForCluster = metrics.getOrElse(clusterName, throw new IllegalArgumentException(s"No metric for the ${clusterName} registered"))
    metricsForCluster.getOrElse(gaugeDefinition, throw new IllegalArgumentException(s"No metric with definition ${gaugeDefinition.name} registered"))
  }
}
