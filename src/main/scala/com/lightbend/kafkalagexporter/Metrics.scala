package com.lightbend.kafkalagexporter

import com.lightbend.kafkalagexporter.MetricsSink._

object Metrics {
  final case class LatestOffsetMetric(clusterName: String, topicPartition: Domain.TopicPartition, value: Double)
    extends Message with Metric {
    override def labels: List[String] =
      List(
        clusterName,
        topicPartition.topic,
        topicPartition.partition.toString
      )
  }

  sealed trait GroupMessage extends Message with Metric {
    def clusterName: String
    def group: Domain.ConsumerGroup

    override def labels: List[String] =
      List(
        clusterName,
        group.id,
        group.state,
        group.isSimpleGroup.toString
      )
  }

  final case class MaxGroupOffsetLagMetric(clusterName: String, group: Domain.ConsumerGroup, value: Double) extends GroupMessage
  final case class MaxGroupTimeLagMetric(clusterName: String, group: Domain.ConsumerGroup, value: Double) extends GroupMessage

  sealed trait GroupPartitionMessage extends Message with Metric {
    def clusterName: String
    def gtp: Domain.GroupTopicPartition
    def member: Domain.ConsumerGroupMember

    override def labels: List[String] =
      List(
        clusterName,
        gtp.group.id,
        gtp.topicPartition.topic,
        gtp.topicPartition.partition.toString,
        gtp.group.state,
        gtp.group.isSimpleGroup.toString,
        member.host,
        member.consumerId,
        member.clientId)
  }

  final case class LastGroupOffsetMetric(clusterName: String, gtp: Domain.GroupTopicPartition, member: Domain.ConsumerGroupMember, value: Double) extends GroupPartitionMessage
  final case class OffsetLagMetric(clusterName: String, gtp: Domain.GroupTopicPartition, member: Domain.ConsumerGroupMember, value: Double) extends GroupPartitionMessage
  final case class TimeLagMetric(clusterName: String, gtp: Domain.GroupTopicPartition, member: Domain.ConsumerGroupMember, value: Double) extends GroupPartitionMessage

  val metricDefinitions: MetricDefinitions = Map(
    classOf[LatestOffsetMetric] -> GaugeDefinition(
      "kafka_partition_latest_offset",
      "Latest offset of a partition",
      "cluster_name", "topic", "partition"
    ),
    classOf[MaxGroupOffsetLagMetric] -> GaugeDefinition(
      "kafka_consumergroup_group_max_lag",
      "Max group offset lag",
      "cluster_name", "group", "state", "is_simple_consumer"
    ),
    classOf[MaxGroupTimeLagMetric] -> GaugeDefinition(
      "kafka_consumergroup_group_max_lag_seconds",
      "Max group time lag",
      "cluster_name", "group", "state", "is_simple_consumer"
    ),
    classOf[LastGroupOffsetMetric] -> GaugeDefinition(
      "kafka_consumergroup_group_offset",
      "Last group consumed offset of a partition",
      "cluster_name", "group", "topic", "partition", "state", "is_simple_consumer", "member_host", "consumer_id", "client_id"
    ),
    classOf[OffsetLagMetric] -> GaugeDefinition(
      "kafka_consumergroup_group_lag",
      "Group offset lag of a partition",
      "cluster_name", "group", "topic", "partition", "state", "is_simple_consumer", "member_host", "consumer_id", "client_id"
    ),
    classOf[TimeLagMetric] -> GaugeDefinition(
      "kafka_consumergroup_group_lag_seconds",
      "Group time lag of a partition",
      "cluster_name", "group", "topic", "partition", "state", "is_simple_consumer", "member_host", "consumer_id", "client_id"
    )
  )
}
