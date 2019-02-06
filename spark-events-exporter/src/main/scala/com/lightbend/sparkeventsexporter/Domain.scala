package com.lightbend.sparkeventsexporter

object Domain {
  final case class TopicPartitionOffset(topic: String, partition: Int, offset: Long)

  final case class SourceMetrics(
                                  inputRecordsPerSecond: Double,
                                  outputRecordsPerSecond: Double,
                                  endOffsets: List[TopicPartitionOffset]
                                )

  final case class Query(id: String, timestamp: Long, sourceMetrics: List[SourceMetrics])
}
