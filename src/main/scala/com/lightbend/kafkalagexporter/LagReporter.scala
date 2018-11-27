package com.lightbend.kafkalagexporter

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import io.prometheus.client.{Gauge, Summary}
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports

import scala.concurrent.duration.FiniteDuration

object LagReporter {
  sealed trait Message
  case class LatestOffsetMetric(topicPartition: Offsets.TopicPartition, offset: Long) extends Message
  sealed trait GroupMessage extends Message {
    def gtp: Offsets.GroupTopicPartition
    def member: Offsets.ConsumerGroupMember
  }
  case class LastGroupOffsetMetric(gtp: Offsets.GroupTopicPartition, member: Offsets.ConsumerGroupMember, offset: Long) extends GroupMessage
  case class OffsetLagMetric(gtp: Offsets.GroupTopicPartition, member: Offsets.ConsumerGroupMember, lag: Long) extends GroupMessage
  case class TimeLagMetric(gtp: Offsets.GroupTopicPartition, member: Offsets.ConsumerGroupMember, lag: FiniteDuration) extends GroupMessage

  def init(appConfig: AppConfig, exporterCreator: () => HTTPServer): Behavior[Message] = Behaviors.setup { _ =>
    DefaultExports.initialize()
    val metrics = new Metrics()
    reporter(appConfig, exporterCreator(), metrics)
  }

  def reporter(appConfig: AppConfig, exporter: HTTPServer, metrics: Metrics): Behavior[LagReporter.Message] = Behaviors.receiveMessage {
    case m: LagReporter.LatestOffsetMetric =>
      metrics.latestOffset
        .labels(
          m.topicPartition.topic,
          m.topicPartition.partition.toString)
        .set(m.offset)
      Behaviors.same
    case m: LagReporter.LastGroupOffsetMetric =>
      metrics.lastGroupOffset
        .labels(groupLabelValues(m): _*)
        .set(m.offset)
      Behaviors.same
    case m: LagReporter.OffsetLagMetric =>
      metrics.offsetLag
        .labels(groupLabelValues(m): _*)
        .set(m.lag)
      Behaviors.same
    case m: LagReporter.TimeLagMetric =>
      val lagSecondsAsDouble: Double = m.lag.toMillis.toDouble / 1000
      metrics.timeLag
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

  private class Metrics {
    val latestOffset: Gauge = Gauge.build()
      .name("kafka_lag_exporter_latest_offset")
      .help("Latest offset of a partition")
      .labelNames("topic", "partition")
      .register()
    val lastGroupOffset: Gauge = Gauge.build()
      .name("kafka_lag_exporter_group_offset")
      .help("Last consumed offset of a partition")
      .labelNames("group", "topic", "partition", "state", "isSimpleConsumer", "memberHost", "consumerId", "clientId")
      .register()
    val offsetLag: Gauge = Gauge.build()
      .name("kafka_lag_exporter_group_lag")
      .help("Offset lag")
      .labelNames("group", "topic", "partition", "state", "isSimpleConsumer", "memberHost", "consumerId", "clientId")
      .register()
    val timeLag: Gauge = Gauge.build()
      .name("kafka_lag_exporter_group_lag_seconds")
      .help("Time lag")
      .labelNames("group", "topic", "partition", "state", "isSimpleConsumer", "memberHost", "consumerId", "clientId")
      .register()
  }
}