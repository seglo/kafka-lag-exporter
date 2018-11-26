package com.lightbend.kafkalagexporter

import scala.concurrent.duration.FiniteDuration

object Offsets {
  case class TopicPartition(topic: String, partition: Int)
  case class GroupTopicPartition(group: ConsumerGroup, topicPartition: TopicPartition)

  sealed trait Measurement {
    def addMeasurement(single: Single): Double
    def offsetLag(lastOffset: Long): Long
  }

  case class Single(offset: Long, timestamp: Long) extends Measurement {
    def addMeasurement(b: Single): Double = Double(this, b)
    def offsetLag(lastOffset: Long): Long = lastOffset - offset
  }

  case class Double(a: Single, b: Single) extends Measurement {
    def addMeasurement(c: Single): Double = Double(b, c)

    def offsetLag(lastOffset: Long): Long = {
      if (lastOffset <= 0) 0
      else {
        lastOffset - b.offset
      }
    }

    def lag(now: Long, lastOffset: Long): Long = {
      if (lastOffset <= b.offset || b.offset - a.offset == 0) 0
      else {
        // linear extrapolation, solve for the x intercept given y (lastOffset), slope (dy/dx), and two points (a, b)
        val dx = (b.timestamp - a.timestamp).toDouble
        val dy = (b.offset - a.offset).toDouble
        val Px = b.timestamp
        val Dy = (b.offset - lastOffset).toDouble

        val lagPx = Px - (now + (Dy*(dx/dy)))

        //println(s"lagPx = $lagPx = $Px - ($now + ($Dy*($dx/$dy)))")

        lagPx.toLong
      }
    }
  }

  case class LagMetric(now: Long, latestOffset: Long, measurement: Offsets.Double) {
    val lagOffsets: Long = measurement.offsetLag(latestOffset)
    val lagMs: FiniteDuration = FiniteDuration(measurement.lag(now, latestOffset), scala.concurrent.duration.MILLISECONDS)
    override def toString(): String = s"now: $now, lagMs: $lagMs, lagOffsets: $lagOffsets, latestOffset: $latestOffset, measurement: $measurement"
  }

  case class ConsumerGroup(id: String, isSimpleGroup: Boolean, state: String, members: List[ConsumerGroupMember])
  case class ConsumerGroupMember(clientId: String, consumerId: String, host: String, partitions: Set[TopicPartition])

  case class LastCommittedOffsets(map: Map[GroupTopicPartition, Measurement]) {
    def addOrUpdate(gtp: GroupTopicPartition, measurement: Measurement) = map.updated(gtp, measurement)
    def get(gtp: GroupTopicPartition) = map.get(gtp)
    def getOrElse(gtp: GroupTopicPartition, measurement: Measurement) = map.getOrElse(gtp, measurement)
    def contains(gtp: GroupTopicPartition) = map.contains(gtp)
  }

  object LastCommittedOffsets {
    def apply(): LastCommittedOffsets = LastCommittedOffsets(Map.empty[GroupTopicPartition, Measurement])
  }

  case class LatestOffsets(map: Map[TopicPartition, Long]) {
    def addOrUpdate(tp: TopicPartition, offset: Long) = map.updated(tp, offset)
    def get(tp: TopicPartition) = map.get(tp)
    def contains(tp: TopicPartition) = map.contains(tp)
  }

  object LatestOffsets {
    def apply(): LatestOffsets = LatestOffsets(Map.empty[TopicPartition, Long])
  }

}
