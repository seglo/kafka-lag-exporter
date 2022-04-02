/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2022 Sean Glover <https://seanglover.com>
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
