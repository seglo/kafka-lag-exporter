package com.lightbend.kafkalagexporter

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import com.lightbend.kafkalagexporter.PrometheusMetricsEndpoint.PrometheusMetricsEndpointContract

import scala.concurrent.duration.FiniteDuration

object LagReporter {
  sealed trait Message
  case class LatestOffsetMetric(topicPartition: Domain.TopicPartition, offset: Long) extends Message

  sealed trait GroupMessage extends Message {
    def group: Domain.ConsumerGroup
  }

  case class MaxGroupOffsetLagMetric(group: Domain.ConsumerGroup, lag: Long) extends GroupMessage
  case class MaxGroupTimeLagMetric(group: Domain.ConsumerGroup, lag: FiniteDuration) extends GroupMessage


  sealed trait GroupPartitionMessage extends Message {
    def gtp: Domain.GroupTopicPartition
    def member: Domain.ConsumerGroupMember
  }

  case class LastGroupOffsetMetric(gtp: Domain.GroupTopicPartition, member: Domain.ConsumerGroupMember, offset: Long) extends GroupPartitionMessage
  case class OffsetLagMetric(gtp: Domain.GroupTopicPartition, member: Domain.ConsumerGroupMember, lag: Long) extends GroupPartitionMessage
  case class TimeLagMetric(gtp: Domain.GroupTopicPartition, member: Domain.ConsumerGroupMember, lag: FiniteDuration) extends GroupPartitionMessage

  def init(appConfig: AppConfig, endpointCreator: () => PrometheusMetricsEndpointContract): Behavior[Message] = Behaviors.setup { _ =>
    reporter(appConfig, endpointCreator())
  }

  def reporter(appConfig: AppConfig, endpoint: PrometheusMetricsEndpointContract): Behavior[LagReporter.Message] = Behaviors.receiveMessage {
    case m: LagReporter.LatestOffsetMetric =>
      endpoint.latestOffset
        .labels(
          m.topicPartition.topic,
          m.topicPartition.partition.toString)
        .set(m.offset)
      Behaviors.same
    case m: LagReporter.MaxGroupOffsetLagMetric =>
      endpoint.maxOffsetLag
        .labels(groupLabels(m): _*)
        .set(m.lag)
      Behaviors.same
    case m: LagReporter.MaxGroupTimeLagMetric =>
      val lagSecondsAsDouble: Double = m.lag.toMillis.toDouble / 1000
      endpoint.maxTimeLag
        .labels(groupLabels(m): _*)
        .set(lagSecondsAsDouble)
      Behaviors.same
    case m: LagReporter.LastGroupOffsetMetric =>
      endpoint.lastGroupOffset
        .labels(groupPartitionLabels(m): _*)
        .set(m.offset)
      Behaviors.same
    case m: LagReporter.OffsetLagMetric =>
      endpoint.offsetLag
        .labels(groupPartitionLabels(m): _*)
        .set(m.lag)
      Behaviors.same
    case m: LagReporter.TimeLagMetric =>
      val lagSecondsAsDouble: Double = m.lag.toMillis.toDouble / 1000
      endpoint.timeLag
        .labels(groupPartitionLabels(m): _*)
        .set(lagSecondsAsDouble)
      Behaviors.same
  }

  private def groupLabels(m: GroupMessage): List[String] = {
    List(
      m.group.id,
      m.group.state,
      m.group.isSimpleGroup.toString
    )
  }

  private def groupPartitionLabels(m: GroupPartitionMessage): List[String] = {
    List(
      m.gtp.group.id,
      m.gtp.topicPartition.topic,
      m.gtp.topicPartition.partition.toString,
      m.gtp.group.state,
      m.gtp.group.isSimpleGroup.toString,
      m.member.host,
      m.member.consumerId,
      m.member.clientId)
  }
}