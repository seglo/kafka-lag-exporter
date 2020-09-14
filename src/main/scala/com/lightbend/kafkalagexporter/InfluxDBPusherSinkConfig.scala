/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import com.typesafe.config.Config

class InfluxDBPusherSinkConfig(sinkType: String, metricWhitelist: List[String], config: Config) extends SinkConfig(sinkType, metricWhitelist, config)
{
    val endpoint: String = config.getString("reporters.influxDB.endpoint")
    val port: Int = config.getInt("reporters.influxDB.port")
    val defaultDatabase: String = "kafka_lag_exporter"
    val database: String = if (config.hasPath("reporters.influxDB.database")) config.getString("reporters.influxDB.database") else defaultDatabase
    val username: String = if (config.hasPath("reporters.influxDB.username")) config.getString("reporters.influxDB.username") else ""
    val password: String = if (config.hasPath("reporters.influxDB.password")) config.getString("reporters.influxDB.password") else ""
    val async: Boolean = if (config.hasPath("reporters.influxDB.async")) config.getBoolean("reporters.influxDB.async") else true

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
