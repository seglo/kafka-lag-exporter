/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import com.typesafe.config.Config

class PrometheusEndpointSinkConfig(sinkType: String, metricWhitelist: List[String], config: Config) extends SinkConfig(sinkType, metricWhitelist, config)
{
  val port: Int = config.getInt("port")
}
