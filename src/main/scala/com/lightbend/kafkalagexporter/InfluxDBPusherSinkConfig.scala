/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import com.typesafe.config.Config

class InfluxDBPusherSinkConfig(sinkType: String, metricWhitelist: List[String], config: Config) extends SinkConfig(sinkType, metricWhitelist, config)
{
    val endPoint: String = config.getString("end-point")
    val port: Int = config.getInt("port")
    val defaultDatabase: String = "kafka_lag_exporter"
    var databaseName: String = if (config.hasPath("database")) config.getString("database") else defaultDatabase
    var username: String = if (config.hasPath("username")) config.getString("username") else ""
    val password: String = if (config.hasPath("password")) config.getString("password") else ""
    val async: Boolean = if (config.hasPath("async")) config.getBoolean("async") else true
}
