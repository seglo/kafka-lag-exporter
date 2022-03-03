/*
 * Copyright (C) 2019-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import com.typesafe.config.Config
import java.net.ServerSocket
import scala.util.{Failure, Success, Try}

class PrometheusEndpointSinkConfig(sinkType: String, metricWhitelist: List[String], config: Config) extends SinkConfig(sinkType, metricWhitelist, config)
{
  val port: Int = getPort(config)

  def getPort(config: Config): Int = {
    if (config.hasPath("port")) { // legacy
      config.getInt("port")
    }
    else if (config.hasPath("reporters.prometheus.port")) {
      config.getInt("reporters.prometheus.port")
    }
    else {
      Try(new ServerSocket(0)) match {
        case Success(socket) =>
          val freePort = socket.getLocalPort
          socket.close()
          freePort
        case Failure(exception) => throw exception
        }
    }
  }

  override def toString(): String = {
    s"""
      |Prometheus:
      |  port: ${port}
    """
  }
}
