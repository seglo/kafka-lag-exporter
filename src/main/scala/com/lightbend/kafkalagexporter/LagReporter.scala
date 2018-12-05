package com.lightbend.kafkalagexporter

import akka.actor.typed.{Behavior, PostStop}
import akka.actor.typed.scaladsl.Behaviors
import com.lightbend.kafkalagexporter.PrometheusMetricsEndpoint.PrometheusMetricsEndpointContract

import scala.concurrent.duration.FiniteDuration

object LagReporter {
  sealed trait Message
  sealed trait Stop extends Message
  final case object Stop extends Stop

  final case class LatestOffsetMetric(clusterName: String, topicPartition: Domain.TopicPartition, offset: Long) extends Message

  sealed trait GroupMessage extends Message {
    def clusterName: String
    def group: Domain.ConsumerGroup
  }

  final case class MaxGroupOffsetLagMetric(clusterName: String, group: Domain.ConsumerGroup, lag: Long) extends GroupMessage
  final case class MaxGroupTimeLagMetric(clusterName: String, group: Domain.ConsumerGroup, lag: FiniteDuration) extends GroupMessage

  sealed trait GroupPartitionMessage extends Message {
    def clusterName: String
    def gtp: Domain.GroupTopicPartition
    def member: Domain.ConsumerGroupMember
  }

  final case class LastGroupOffsetMetric(clusterName: String, gtp: Domain.GroupTopicPartition, member: Domain.ConsumerGroupMember, offset: Long) extends GroupPartitionMessage
  final case class OffsetLagMetric(clusterName: String, gtp: Domain.GroupTopicPartition, member: Domain.ConsumerGroupMember, lag: Long) extends GroupPartitionMessage
  final case class TimeLagMetric(clusterName: String, gtp: Domain.GroupTopicPartition, member: Domain.ConsumerGroupMember, lag: FiniteDuration) extends GroupPartitionMessage

  def init(appConfig: AppConfig, endpointCreator: () => PrometheusMetricsEndpointContract): Behavior[Message] = Behaviors.setup { _ =>
    reporter(appConfig, endpointCreator())
  }

  def reporter(appConfig: AppConfig, endpoint: PrometheusMetricsEndpointContract): Behavior[LagReporter.Message] = Behaviors.receive {
    case (_, m: LagReporter.LatestOffsetMetric) =>
      endpoint.latestOffset
        .labels(
          m.clusterName,
          m.topicPartition.topic,
          m.topicPartition.partition.toString)
        .set(m.offset)
      Behaviors.same
    case (_, m: LagReporter.MaxGroupOffsetLagMetric) =>
      endpoint.maxOffsetLag
        .labels(groupLabels(m): _*)
        .set(m.lag)
      Behaviors.same
    case (_, m: LagReporter.MaxGroupTimeLagMetric) =>
      val lagSecondsAsDouble: Double = m.lag.toMillis.toDouble / 1000
      endpoint.maxTimeLag
        .labels(groupLabels(m): _*)
        .set(lagSecondsAsDouble)
      Behaviors.same
    case (_, m: LagReporter.LastGroupOffsetMetric) =>
      endpoint.lastGroupOffset
        .labels(groupPartitionLabels(m): _*)
        .set(m.offset)
      Behaviors.same
    case (_, m: LagReporter.OffsetLagMetric) =>
      endpoint.offsetLag
        .labels(groupPartitionLabels(m): _*)
        .set(m.lag)
      Behaviors.same
    case (_, m: LagReporter.TimeLagMetric) =>
      val lagSecondsAsDouble: Double = m.lag.toMillis.toDouble / 1000
      endpoint.timeLag
        .labels(groupPartitionLabels(m): _*)
        .set(lagSecondsAsDouble)
      Behaviors.same
    case (context, _: LagReporter.Stop) =>
      Behaviors.stopped {
        Behaviors.receiveSignal {
          case (_, PostStop) =>
            endpoint.stop()
            context.log.info("Gracefully stopped Prometheus metrics endpoint HTTP server")
            Behaviors.same
        }
      }
  }

  private def groupLabels(m: GroupMessage): List[String] = {
    List(
      m.clusterName,
      m.group.id,
      m.group.state,
      m.group.isSimpleGroup.toString
    )
  }

  private def groupPartitionLabels(m: GroupPartitionMessage): List[String] = {
    List(
      m.clusterName,
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