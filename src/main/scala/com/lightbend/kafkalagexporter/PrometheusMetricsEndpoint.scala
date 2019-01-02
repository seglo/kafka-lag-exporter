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
    def stop(): Unit
  }
}

class PrometheusMetricsEndpoint private(httpPort: Int) extends PrometheusMetricsEndpointContract {
  private val server = new HTTPServer(httpPort)
  DefaultExports.initialize()

  val latestOffset: Gauge = Gauge.build()
    .name("kafka_partition_latest_offset")
    .help("Latest offset of a partition")
    .labelNames("cluster_name", "topic", "partition")
    .register()
  val maxOffsetLag: Gauge = Gauge.build()
    .name("kafka_consumergroup_group_max_lag")
    .help("Max group offset lag")
    .labelNames("cluster_name", "group", "state", "is_simple_consumer")
    .register()
  val maxTimeLag: Gauge = Gauge.build()
    .name("kafka_consumergroup_group_max_lag_seconds")
    .help("Max group time lag")
    .labelNames("cluster_name", "group", "state", "is_simple_consumer")
    .register()
  val lastGroupOffset: Gauge = Gauge.build()
    .name("kafka_consumergroup_group_offset")
    .help("Last group consumed offset of a partition")
    .labelNames("cluster_name", "group", "topic", "partition", "state", "is_simple_consumer", "member_host", "consumer_id", "client_id")
    .register()
  val offsetLag: Gauge = Gauge.build()
    .name("kafka_consumergroup_group_lag")
    .help("Group offset lag of a partition")
    .labelNames("cluster_name", "group", "topic", "partition", "state", "is_simple_consumer", "member_host", "consumer_id", "client_id")
    .register()
  val timeLag: Gauge = Gauge.build()
    .name("kafka_consumergroup_group_lag_seconds")
    .help("Group time lag of a partition")
    .labelNames("cluster_name", "group", "topic", "partition", "state", "is_simple_consumer", "member_host", "consumer_id", "client_id")
    .register()

  def stop(): Unit = server.stop()
}
