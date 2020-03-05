/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import com.typesafe.config.Config
import java.net.ServerSocket
import scala.util.{Failure, Success, Try}

class PrometheusEndpointSinkConfig(sinkType: String, metricWhitelist: List[String], config: Config) extends SinkConfig(sinkType, metricWhitelist, config)
{
  val port: Int = getPort(config)

  def getPort(config: Config): Int = {
    if (config.hasPath("port"))
      config.getInt("port")
    else
      Try(new ServerSocket(0)) match {
        case Success(socket) =>
          val freePort = socket.getLocalPort
          socket.close()
          freePort
        case Failure(exception) => throw exception
        }
  }
}
