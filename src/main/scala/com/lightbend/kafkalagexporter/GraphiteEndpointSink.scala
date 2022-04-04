/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2022 Sean Glover <https://seanglover.com>
 */

package com.lightbend.kafkalagexporter

import com.lightbend.kafkalagexporter.EndpointSink.ClusterGlobalLabels
import com.lightbend.kafkalagexporter.MetricsSink._
import java.io.PrintWriter
import java.net.Socket
import scala.util.Try
import scala.util.Failure
import scala.util.Success

object GraphiteEndpointSink {

  def apply(
      graphiteConfig: GraphiteEndpointConfig,
      clusterGlobalLabels: ClusterGlobalLabels
  ): MetricsSink = {
    Try(new GraphiteEndpointSink(graphiteConfig, clusterGlobalLabels))
      .fold(
        t => throw new Exception("Could not create Graphite Endpoint", t),
        sink => sink
      )
  }
}

class GraphiteEndpointSink private (
    graphiteConfig: GraphiteEndpointConfig,
    clusterGlobalLabels: ClusterGlobalLabels
) extends EndpointSink(clusterGlobalLabels) {
  def graphitePush(
      graphiteConfig: GraphiteEndpointConfig,
      metricName: String,
      metricValue: Double
  ): Unit = {
    Try(new Socket(graphiteConfig.host, graphiteConfig.port)) match {
      case Success(socket) =>
        Try(new PrintWriter(socket.getOutputStream)) match {
          case Success(writer) =>
            writer.print(
              s"${graphiteConfig.prefix.getOrElse("")}${metricName} ${metricValue} ${System.currentTimeMillis / 1000}\n"
            )
            writer.close
            socket.close
          case Failure(_) =>
            socket.close
        }
      case Failure(_) => {}
    }
  }

  /** get graphite metric name for a metric value
    *
    * @example
    *   { label1=value1, label2=value2, name=myName } => "value1.value2.myName"
    */
  def metricNameToGraphiteMetricName(metricValue: MetricValue): String = {
    (getGlobalLabelValuesOrDefault(
      metricValue.clusterName
    ) ++ metricValue.labels)
      .map(x => x.replaceAll("\\.", "_"))
      .mkString(".") + "." + metricValue.definition.name;
  }

  override def report(m: MetricValue): Unit = {
    if (graphiteConfig.metricWhitelist.exists(m.definition.name.matches)) {
      graphitePush(graphiteConfig, metricNameToGraphiteMetricName(m), m.value);
    }
  }

  override def remove(m: RemoveMetric): Unit = {}

}
