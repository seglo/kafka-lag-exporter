package com.lightbend.kafkalagexporter

import com.lightbend.kafkalagexporter.Domain.Measurements.{Measurement, Single}

import scala.concurrent.duration.FiniteDuration

object Domain {
  object Measurements {

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
        if (lastOffset <= 0 || b.offset > lastOffset) 0
        else {
          lastOffset - b.offset
        }
      }

      def lag(lastOffset: Long): FiniteDuration = {
        val lagMs =
          if (lastOffset <= b.offset || b.offset - a.offset == 0) 0
          else {
            val now = b.timestamp
            // linear extrapolation, solve for the x intercept given y (lastOffset), slope (dy/dx), and two points (a, b)
            val dx = (b.timestamp - a.timestamp).toDouble
            val dy = (b.offset - a.offset).toDouble
            val Px = b.timestamp
            val Dy = (b.offset - lastOffset).toDouble

            val lagPx = Px - (now + (Dy * (dx / dy)))

            //println(s"lagPx = $lagPx = $Px - ($now + ($Dy*($dx/$dy)))")

            lagPx.toLong
          }

        FiniteDuration(lagMs, scala.concurrent.duration.MILLISECONDS)
      }
    }

  }

  case class TopicPartition(topic: String, partition: Int)
  case class GroupTopicPartition(group: ConsumerGroup, topicPartition: TopicPartition)

  case class ConsumerGroup(id: String, isSimpleGroup: Boolean, state: String, members: List[ConsumerGroupMember])
  case class ConsumerGroupMember(clientId: String, consumerId: String, host: String, partitions: Set[TopicPartition])

  type LastCommittedOffsets = Map[GroupTopicPartition, Measurement]

  object LastCommittedOffsets {
    def apply(): LastCommittedOffsets = Map.empty[GroupTopicPartition, Measurement]
  }

  type LatestOffsets = Map[TopicPartition, Single]

  object LatestOffsets {
    def apply(): LatestOffsets = Map.empty[TopicPartition, Single]
  }
}
