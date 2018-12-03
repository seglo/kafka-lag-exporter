package com.lightbend.kafkalagexporter

import com.lightbend.kafkalagexporter.PrometheusMetricsEndpoint.PrometheusMetricsEndpointContract
import io.prometheus.client.Gauge
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports

object PrometheusMetricsEndpoint {
  def apply(httpPort: Int): PrometheusMetricsEndpointContract = new PrometheusMetricsEndpoint(httpPort)

  trait PrometheusMetricsEndpointContract {
    def latestOffset: Gauge
    def maxOffsetLag: Gauge
    def maxTimeLag: Gauge
    def lastGroupOffset: Gauge
    def offsetLag: Gauge
    def timeLag: Gauge
  }
}

class PrometheusMetricsEndpoint private(httpPort: Int) extends PrometheusMetricsEndpointContract {
  private val server = new HTTPServer(httpPort)
  DefaultExports.initialize()

  val latestOffset: Gauge = Gauge.build()
    .name("kafka_consumergroup_latest_offset")
    .help("Latest offset of a partition")
    .labelNames("topic", "partition")
    .register()
  val maxOffsetLag: Gauge = Gauge.build()
    .name("kafka_consumergroup_group_max_lag")
    .help("Max group offset lag")
    .labelNames("group", "state", "isSimpleConsumer")
    .register()
  val maxTimeLag: Gauge = Gauge.build()
    .name("kafka_consumergroup_group_max_lag_seconds")
    .help("Max group time lag")
    .labelNames("group", "state", "isSimpleConsumer")
    .register()
  val lastGroupOffset: Gauge = Gauge.build()
    .name("kafka_consumergroup_group_offset")
    .help("Last group consumed offset of a partition")
    .labelNames("group", "topic", "partition", "state", "isSimpleConsumer", "memberHost", "consumerId", "clientId")
    .register()
  val offsetLag: Gauge = Gauge.build()
    .name("kafka_consumergroup_group_lag")
    .help("Group offset lag of a partition")
    .labelNames("group", "topic", "partition", "state", "isSimpleConsumer", "memberHost", "consumerId", "clientId")
    .register()
  val timeLag: Gauge = Gauge.build()
    .name("kafka_consumergroup_group_lag_seconds")
    .help("Group time lag of a partition")
    .labelNames("group", "topic", "partition", "state", "isSimpleConsumer", "memberHost", "consumerId", "clientId")
    .register()
}
