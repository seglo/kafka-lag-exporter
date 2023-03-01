package com.lightbend.kafkalagexporter

import com.typesafe.config.Config

class InfluxDB2PusherSinkConfig(
                                 sinkType: String,
                                 metricWhitelist: List[String],
                                 config: Config
                               ) extends SinkConfig(sinkType, metricWhitelist, config)
{

  val endpoint: String = config.getString("reporters.influxdb2.endpoint")
  val port: Int = config.getInt("reporters.influxdb2.port")
  val bucket: String =
    if (config.hasPath("reporters.influxdb2.bucket"))
      config.getString("reporters.influxdb2.bucket")
    else "kafka_lag_exporter"
  val token : String =
    if(config.hasPath("reporters.influxdb2.token"))
      config.getString("reporters.influxdb2.token")
    else ""
  val orgName: String =
    if (config.hasPath("reporters.influxdb2.org-name"))
      config.getString("reporters.influxdb2.org-name")
    else ""
  val orgId: String =
    if (config.hasPath("reporters.influxdb2.org-id"))
      config.getString("reporters.influxdb2.org-id")
    else ""
  val username: String =
    if (config.hasPath("reporters.influxdb2.username"))
      config.getString("reporters.influxdb2.username")
    else ""
  val password: String =
    if (config.hasPath("reporters.influxdb2.password"))
      config.getString("reporters.influxdb2.password")
    else ""
   val retentionSeconds : Long =
    if (config.hasPath("reporters.influxdb2.retention-seconds"))
      config.getLong("reporters.influxdb2.retention-seconds")
    else 60 * 60 * 24 * 7


}



