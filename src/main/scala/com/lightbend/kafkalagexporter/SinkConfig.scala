/*
 * Copyright (C) 2019-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter
import com.typesafe.config.{Config}

abstract class SinkConfig(val sinkType: String, val metricWhitelist: List[String], val config: Config)
{
  override def toString(): String = {
    ""
  }
}
