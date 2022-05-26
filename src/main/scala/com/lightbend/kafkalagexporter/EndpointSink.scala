/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2022 Sean Glover <https://seanglover.com>
 */

package com.lightbend.kafkalagexporter

import com.lightbend.kafkalagexporter.EndpointSink.{
  ClusterGlobalLabels,
  ClusterName
}
import io.prometheus.client.hotspot.DefaultExports

object EndpointSink {
  type ClusterName = String
  type GlobalLabels = Map[String, String]
  type ClusterGlobalLabels = Map[ClusterName, GlobalLabels]
}

abstract class EndpointSink(clusterGlobalLabels: ClusterGlobalLabels)
    extends MetricsSink {
  DefaultExports.initialize()

  private[kafkalagexporter] val globalLabelNames: List[String] = {
    clusterGlobalLabels.values.flatMap(_.keys).toList.distinct
  }

  def getGlobalLabelValuesOrDefault(clusterName: ClusterName): List[String] = {
    val globalLabelValuesForCluster =
      clusterGlobalLabels.getOrElse(clusterName, Map.empty)
    globalLabelNames.map(l => globalLabelValuesForCluster.getOrElse(l, ""))
  }
}
