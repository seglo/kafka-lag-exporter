package com.lightbend.kafka.sparkeventexporter.internal
import java.time.Instant

import com.lightbend.kafka.core.Domain.{Measurements, TopicPartition}
import com.lightbend.kafka.sparkeventexporter.internal.Domain.{Query, SourceMetrics}
import org.apache.spark.sql.streaming.{SourceProgress, StreamingQueryProgress}
import org.json4s.JsonAST.{JInt, JObject}
import org.json4s.jackson.JsonMethods.parseOpt

import scala.util.Try

object SparkEventAdapter {
  /**
    * Parse a `org.apache.spark.sql.streaming.StreamingQueryProgress` for relevant metric labels and values.
    */
  def parseProgress(qp: StreamingQueryProgress): Query = {
    val sparkQueryId: String = qp.id.toString
    val timestamp: Long = Instant.parse(qp.timestamp).toEpochMilli

    val sourceMetrics = qp.sources.toList.map { sp: SourceProgress =>
      val endOffsets = parseEndOffsets(sp.endOffset, timestamp)
      SourceMetrics(sp.inputRowsPerSecond, sp.processedRowsPerSecond, endOffsets)
    }


    Query(sparkQueryId, timestamp, sourceMetrics)
  }

  /**
    * Parse the `endOffsets` JSON happy path from the Spark `org.apache.spark.sql.streaming.SourceProgress` object.
    *
    * An example of this JSON could be:
    *
    * ```
    * {
    *   "call-record-pipeline-seglo.cdr-validator.out-1" : {
    *     "0" : 12477,
    *     "1" : 12293,
    *     "2" : 11274
    *   }
    * }
    * ```
    */
  def parseEndOffsets(endOffsetsJson: String, timestamp: Long): Map[TopicPartition, Measurements.Single] = {
    val endOffsets = parseOpt(endOffsetsJson)

    val lastOffsets = for {
      JObject(topicJson) <- endOffsets.toList
      (topic, JObject(offsetsJson)) <- topicJson
      (partitionString, JInt(offsetBigInt)) <- offsetsJson
      offset = offsetBigInt.toLong
      partition <- Try(partitionString.toInt).toOption
    }
      yield TopicPartition(topic, partition) -> Measurements.Single(offset, timestamp)

    lastOffsets.toMap
  }
}
