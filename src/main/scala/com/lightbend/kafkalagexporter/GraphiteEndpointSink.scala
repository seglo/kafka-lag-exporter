/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import com.lightbend.kafkalagexporter.MetricsSink._
import com.lightbend.kafkalagexporter.EndpointSink.ClusterGlobalLabels
import java.net.Socket
import java.io.PrintWriter
import scala.util.{Try, Success, Failure}

import scala.util.Try

object GraphiteEndpointSink {

  def apply(metricWhitelist: List[String], clusterGlobalLabels: ClusterGlobalLabels,
            graphiteConfig: Option[GraphiteConfig]): MetricsSink = {
    Try(new GraphiteEndpointSink(metricWhitelist, clusterGlobalLabels, graphiteConfig))
      .fold(t => throw new Exception("Could not create Graphite Endpoint", t), sink => sink)
  }
}

class GraphiteEndpointSink private(metricWhitelist: List[String], clusterGlobalLabels: ClusterGlobalLabels,
                                      graphiteConfig: Option[GraphiteConfig]) extends EndpointSink(clusterGlobalLabels) {
  def graphitePush(graphiteConfig: GraphiteConfig, metricName: String, metricValue: Double): Unit = {
    Try(new Socket(graphiteConfig.host, graphiteConfig.port)) match {
      case Success(socket) =>
        Try(new PrintWriter(socket.getOutputStream)) match {
          case Success(writer) =>
            writer.print(s"${graphiteConfig.prefix.getOrElse("")}${metricName} ${metricValue} ${System.currentTimeMillis / 1000}\n")
            writer.close
            socket.close
          case Failure(_) =>
            socket.close
        }
      case Failure(_) => {
      }
    }
  }

  /**
    * get graphite metric name for a metric value
    *
    * @example { label1=value1, label2=value2, name=myName } => "value1.value2.myName"
    *
    */ 
  def metricNameToGraphiteMetricName(metricValue: MetricValue): String = {
    (getGlobalLabelValuesOrDefault(metricValue.clusterName) ++ metricValue.labels
      ).map( x => x.replaceAll("\\.", "_")).mkString(".") + "." + metricValue.definition.name;
  }

  override def report(m: MetricValue): Unit = {
    if (metricWhitelist.exists(m.definition.name.matches)) {
      graphiteConfig.foreach { conf =>
        graphitePush(conf, metricNameToGraphiteMetricName(m), m.value);
      }
    }
  }

  override def remove(m: RemoveMetric): Unit = {
  }


}

