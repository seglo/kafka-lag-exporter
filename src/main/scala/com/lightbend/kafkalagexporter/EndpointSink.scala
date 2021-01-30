/*
 * Copyright (C) 2019-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import com.lightbend.kafkalagexporter.EndpointSink.{ClusterGlobalLabels, ClusterName}
import io.prometheus.client.hotspot.DefaultExports

object EndpointSink {
  type ClusterName = String
  type GlobalLabels = Map[String, String]
  type ClusterGlobalLabels = Map[ClusterName, GlobalLabels]
}

abstract class EndpointSink (clusterGlobalLabels: ClusterGlobalLabels) extends MetricsSink {
  DefaultExports.initialize()

  private[kafkalagexporter] val globalLabelNames: List[String] = {
    clusterGlobalLabels.values.flatMap(_.keys).toList.distinct
  }

  def getGlobalLabelValuesOrDefault(clusterName: ClusterName): List[String] = {
    val globalLabelValuesForCluster = clusterGlobalLabels.getOrElse(clusterName, Map.empty)
    globalLabelNames.map(l => globalLabelValuesForCluster.getOrElse(l, ""))
  }
}
