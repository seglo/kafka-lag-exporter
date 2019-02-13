package com.lightbend.kafka.sparkeventexporter.internal
import com.lightbend.kafka.core.Domain.{Measurements, TopicPartition}

object Domain {
  final case class SourceMetrics(
                                  inputRecordsPerSecond: Double,
                                  outputRecordsPerSecond: Double,
                                  endOffsets: Map[TopicPartition, Measurements.Single]
                                )

  final case class Query(sparkAppId: String, timestamp: Long, sourceMetrics: List[SourceMetrics])
}
