package com.lightbend.kafkalagexporter

object Protocol {
  sealed trait Message
  sealed trait Collect extends Message
  case object Collect extends Collect
  case class NewOffsets(
                       now: Long,
                       latestOffsets: Map[Offsets.TopicPartition, Long],
                       lastGroupOffsets: Map[Offsets.ConsumerGroup, Map[Offsets.TopicPartition, Long]])
    extends Message
}
