/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import com.typesafe.config.Config

class InfluxDBPusherSinkConfig(sinkType: String, metricWhitelist: List[String], config: Config) extends SinkConfig(sinkType, metricWhitelist, config)
{
    val endpoint: String = config.getString("reporters.influxdb.endpoint")
    val port: Int = config.getInt("reporters.influxdb.port")
    val defaultDatabase: String = "kafka_lag_exporter"
    val database: String = if (config.hasPath("reporters.influxdb.database")) config.getString("reporters.influxdb.database") else defaultDatabase
    val username: String = if (config.hasPath("reporters.influxdb.username")) config.getString("reporters.influxdb.username") else ""
    val password: String = if (config.hasPath("reporters.influxdb.password")) config.getString("reporters.influxdb.password") else ""
    val async: Boolean = if (config.hasPath("reporters.influxdb.async")) config.getBoolean("reporters.influxdb.async") else true

    override def toString(): String = {
      s"""
        |InfluxDB:
        |  endPoint: ${endpoint}
        |  port: ${port}
        |  database: ${database}
        |  username: ${username}
        |  async: ${async}
      """
    }
}
