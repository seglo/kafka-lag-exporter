/*
 * Copyright (C) 2019-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import com.typesafe.config.Config
import scala.util.Try

class GraphiteEndpointConfig(sinkType: String, metricWhitelist: List[String], config: Config) extends SinkConfig(sinkType, metricWhitelist, config)
{
  val port: Int = config.getInt("reporters.graphite.port")
  val host: String = config.getString("reporters.graphite.host")
  val prefix: Option[String] = Try(config.getString("reporters.graphite.prefix")).toOption

  override def toString(): String = {
    s"""
      |Graphite:
      |  host: ${host}
      |  port: ${port}
      |  prefix: ${prefix}
    """
  }

}
