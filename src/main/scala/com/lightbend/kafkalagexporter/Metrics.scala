/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import com.lightbend.kafkalagexporter.MetricsSink._

object Metrics {
  sealed trait ClusterMessage extends Message with Metric {
    def definition: GaugeDefinition
    def clusterName: String
    override def labels: List[String] =
      List(
        clusterName
      )
  }

  final case class ClusterValueMessage(definition: GaugeDefinition, clusterName: String, value: Double) extends ClusterMessage with MetricValue

  sealed trait TopicPartitionMessage extends Message with Metric {
    def definition: GaugeDefinition
    def clusterName: String
    def topicPartition: Domain.TopicPartition
    override def labels: List[String] =
      List(
        clusterName,
        topicPartition.topic,
        topicPartition.partition.toString
      )
  }

  final case class TopicPartitionValueMessage(definition: GaugeDefinition, clusterName: String, topicPartition: Domain.TopicPartition, value: Double) extends TopicPartitionMessage with MetricValue
  final case class TopicPartitionRemoveMetricMessage(definition: GaugeDefinition, clusterName: String, topicPartition: Domain.TopicPartition) extends TopicPartitionMessage with RemoveMetric

  sealed trait GroupMessage extends Message with Metric {
    def definition: GaugeDefinition
    def clusterName: String
    def group: String
    override def labels: List[String] =
      List(
        clusterName,
        group
      )
  }

  final case class GroupValueMessage(definition: GaugeDefinition, clusterName: String, group: String, value: Double) extends GroupMessage with MetricValue
  final case class GroupRemoveMetricMessage(definition: GaugeDefinition, clusterName: String, group: String) extends GroupMessage with RemoveMetric

  sealed trait GroupPartitionMessage extends Message with Metric {
    def definition: GaugeDefinition
    def clusterName: String
    def gtp: Domain.GroupTopicPartition
    override def labels: List[String] =
      List(
        clusterName,
        gtp.id,
        gtp.topic,
        gtp.partition.toString,
        gtp.host,
        gtp.consumerId,
        gtp.clientId)
  }

  final case class GroupPartitionValueMessage(definition: GaugeDefinition, clusterName: String, gtp: Domain.GroupTopicPartition, value: Double) extends GroupPartitionMessage with MetricValue
  final case class GroupPartitionRemoveMetricMessage(definition: GaugeDefinition, clusterName: String, gtp: Domain.GroupTopicPartition) extends GroupPartitionMessage with RemoveMetric

  sealed trait GroupTopicMessage extends Message with Metric {
    def definition: GaugeDefinition
    def clusterName: String
    def group: String
    def topic: String
    override def labels: List[String] =
      List(
        clusterName,
        group,
        topic
      )
  }

  final case class GroupTopicValueMessage(definition: GaugeDefinition, clusterName: String, group: String, topic: String, value: Double) extends GroupTopicMessage with MetricValue
  final case class GroupTopicRemoveMetricMessage(definition: GaugeDefinition, clusterName: String, group: String, topic: String) extends GroupTopicMessage with RemoveMetric

  val topicPartitionLabels = List("cluster_name", "topic", "partition")

  val LatestOffsetMetric = GaugeDefinition(
    "kafka_partition_latest_offset",
    "Latest offset of a partition",
    topicPartitionLabels
  )

  val EarliestOffsetMetric = GaugeDefinition(
    "kafka_partition_earliest_offset",
    "Earliest offset of a partition",
    topicPartitionLabels
  )

  val groupLabels = List("cluster_name", "group")

  val ClusterLabels = List("cluster_name")


  val MaxGroupOffsetLagMetric = GaugeDefinition(
    "kafka_consumergroup_group_max_lag",
    "Max group offset lag",
    groupLabels
  )

  val MaxGroupTimeLagMetric = GaugeDefinition(
    "kafka_consumergroup_group_max_lag_seconds",
    "Max group time lag",
    groupLabels
  )

  val SumGroupOffsetLagMetric = GaugeDefinition(
    "kafka_consumergroup_group_sum_lag",
    "Sum of group offset lag",
    groupLabels
  )

  val groupPartitionLabels = List("cluster_name", "group", "topic", "partition", "member_host", "consumer_id", "client_id")

  val LastGroupOffsetMetric = GaugeDefinition(
    "kafka_consumergroup_group_offset",
    "Last group consumed offset of a partition",
    groupPartitionLabels
  )

  val OffsetLagMetric = GaugeDefinition(
    "kafka_consumergroup_group_lag",
    "Group offset lag of a partition",
    groupPartitionLabels
  )

  val TimeLagMetric = GaugeDefinition(
    "kafka_consumergroup_group_lag_seconds",
    "Group time lag of a partition",
    groupPartitionLabels
  )

  val groupTopicLabels = List("cluster_name", "group", "topic")

  val SumGroupTopicOffsetLagMetric = GaugeDefinition(
    "kafka_consumergroup_group_topic_sum_lag",
    "Sum of group offset lag across topic partitions",
    groupTopicLabels
  )

  val PollTimeMetric = GaugeDefinition(
    "kafka_consumergroup_poll_time_ms",
    "Group time poll time",
    ClusterLabels
  )

  val definitions = List(
    LatestOffsetMetric,
    EarliestOffsetMetric,
    MaxGroupOffsetLagMetric,
    MaxGroupTimeLagMetric,
    LastGroupOffsetMetric,
    OffsetLagMetric,
    TimeLagMetric,
    SumGroupOffsetLagMetric,
    SumGroupTopicOffsetLagMetric,
    PollTimeMetric
  )
}