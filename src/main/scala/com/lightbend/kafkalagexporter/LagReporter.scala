package com.lightbend.kafkalagexporter

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import com.lightbend.kafkalagexporter.PrometheusMetricsEndpoint.PrometheusMetricsEndpointContract

import scala.concurrent.duration.FiniteDuration

object LagReporter {
  sealed trait Message
  case class LatestOffsetMetric(topicPartition: Domain.TopicPartition, offset: Long) extends Message
  sealed trait GroupMessage extends Message {
    def gtp: Domain.GroupTopicPartition
    def member: Domain.ConsumerGroupMember
  }
  case class LastGroupOffsetMetric(gtp: Domain.GroupTopicPartition, member: Domain.ConsumerGroupMember, offset: Long) extends GroupMessage
  case class OffsetLagMetric(gtp: Domain.GroupTopicPartition, member: Domain.ConsumerGroupMember, lag: Long) extends GroupMessage
  case class TimeLagMetric(gtp: Domain.GroupTopicPartition, member: Domain.ConsumerGroupMember, lag: FiniteDuration) extends GroupMessage

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
    case m: LagReporter.LastGroupOffsetMetric =>
      endpoint.lastGroupOffset
        .labels(groupLabelValues(m): _*)
        .set(m.offset)
      Behaviors.same
    case m: LagReporter.OffsetLagMetric =>
      endpoint.offsetLag
        .labels(groupLabelValues(m): _*)
        .set(m.lag)
      Behaviors.same
    case m: LagReporter.TimeLagMetric =>
      val lagSecondsAsDouble: Double = m.lag.toMillis.toDouble / 1000
      endpoint.timeLag
        .labels(groupLabelValues(m): _*)
        .set(lagSecondsAsDouble)
      Behaviors.same
  }

  private def groupLabelValues(m: GroupMessage): List[String] = {
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