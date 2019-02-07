package com.lightbend.sparkeventsexporter
import com.lightbend.kafkaclientmetrics.Domain.TopicPartition
import com.lightbend.kafkaclientmetrics.PrometheusEndpoint.{GaugeDefinition, Message, Metric}

object Metrics {
  sealed trait SparkOffsetMetric extends Message with Metric {
    def clusterName: String
    def sparkAppId: String
    def name: String
    def topicPartition: TopicPartition

    override def labels: List[String] =
      List(
        clusterName,
        sparkAppId,
        name,
        topicPartition.topic,
        topicPartition.partition.toString
      )
  }

  final case class LatestOffsetMetric(clusterName: String, sparkAppId: String, name: String, topicPartition: TopicPartition, value: Double) extends SparkOffsetMetric
  final case class LastOffsetMetric(clusterName: String, sparkAppId: String, name: String, topicPartition: TopicPartition, value: Double) extends SparkOffsetMetric
  final case class OffsetLagMetric(clusterName: String, sparkAppId: String, name: String, topicPartition: TopicPartition, value: Double) extends SparkOffsetMetric
  final case class TimeLagMetric(clusterName: String, sparkAppId: String, name: String, topicPartition: TopicPartition, value: Double) extends SparkOffsetMetric

  sealed trait SparkThroughputMetric extends Message with Metric {
    def clusterName: String
    def sparkAppId: String
    def name: String
    def sourceTopics: String

    override def labels: List[String] =
      List(
        clusterName,
        sparkAppId,
        name,
        sourceTopics
      )
  }

  final case class InputRecordsPerSecondMetric(clusterName: String, sparkAppId: String, name: String, sourceTopics: String, value: Double) extends SparkThroughputMetric
  final case class OutputRecordsPerSecondMetric(clusterName: String, sparkAppId: String, name: String, sourceTopics: String, value: Double) extends SparkThroughputMetric

  val metricDefinitions: Map[Class[_], GaugeDefinition] = Map(
    LatestOffsetMetric.getClass -> GaugeDefinition(
      "spark_kafka_partition_latest_offset",
      "Latest offset of a partition",
      "cluster_name", "spark_app_id", "provided_name", "topic", "partition"
    ),
    LastOffsetMetric.getClass -> GaugeDefinition(
      "spark_kafka_last_offset",
      "Last consumed offset of a partition",
      "cluster_name", "spark_app_id", "provided_name", "topic", "partition"
    ),
    OffsetLagMetric.getClass -> GaugeDefinition(
      "spark_kafka_last_offset_lag",
      "Last consumed offset lag of a partition",
      "cluster_name", "spark_app_id", "provided_name", "topic", "partition"
    ),
    TimeLagMetric.getClass -> GaugeDefinition(
      "spark_kafka_last_offset_lag_seconds",
      "Last consumed offset time lag of a partition",
      "cluster_name", "spark_app_id", "provided_name", "topic", "partition"
    ),
    InputRecordsPerSecondMetric.getClass -> GaugeDefinition(
      "spark_kafka_input_records_per_second",
      "Input records per second for a source",
      "cluster_name", "spark_app_id", "provided_name", "source_topics"
    ),
    OutputRecordsPerSecondMetric.getClass -> GaugeDefinition(
      "spark_kafka_output_records_per_second",
      "Output/processed records per second for a source",
      "cluster_name", "spark_app_id", "provided_name", "source_topics"
    )
  )
}
