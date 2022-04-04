/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2022 Sean Glover <https://seanglover.com>
 */

package com.lightbend.kafkalagexporter
import com.typesafe.config.{Config}

abstract class SinkConfig(
    val sinkType: String,
    val metricWhitelist: List[String],
    val config: Config
) {
  override def toString(): String = {
    ""
  }
}
