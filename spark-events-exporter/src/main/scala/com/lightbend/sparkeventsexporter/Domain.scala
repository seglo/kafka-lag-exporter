package com.lightbend.sparkeventsexporter
import com.lightbend.kafkaclientmetrics.Domain.{Measurements, TopicPartition}

object Domain {
  final case class SourceMetrics(
                                  inputRecordsPerSecond: Double,
                                  outputRecordsPerSecond: Double,
                                  endOffsets: Map[TopicPartition, Measurements.Single]
                                )

  final case class Query(sparkAppId: String, timestamp: Long, sourceMetrics: List[SourceMetrics])
}
