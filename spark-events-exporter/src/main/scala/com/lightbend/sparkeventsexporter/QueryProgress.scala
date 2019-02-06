package com.lightbend.sparkeventsexporter

import java.time.Instant

import org.apache.spark.sql.streaming.{SourceProgress, StreamingQueryProgress}
import org.json4s._
import org.json4s.jackson.JsonMethods._

import scala.util.Try

import Domain._

object QueryProgress {
  /**
    * Parse a `org.apache.spark.sql.streaming.StreamingQueryProgress` for relevant metric labels and values.
    */
  def parseProgress(qp: StreamingQueryProgress): Query = {
    val sourceMetrics = qp.sources.toList.map { sp: SourceProgress =>
      val endOffsets = parseEndOffsets(sp.endOffset)
      SourceMetrics(sp.inputRowsPerSecond, sp.processedRowsPerSecond, endOffsets)
    }

    val sparkQueryId: String = qp.id.toString
    val timestamp: Long = Instant.parse(qp.timestamp).toEpochMilli

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
  def parseEndOffsets(endOffsetsJson: String): List[TopicPartitionOffset] = {
    val endOffsets = parseOpt(endOffsetsJson)
    for {
      JObject(topicJson) <- endOffsets.toList
      (topic, JObject(offsetsJson)) <- topicJson
      (partitionString, JInt(offsetBigInt)) <- offsetsJson
      offset = offsetBigInt.toLong
      partition <- Try(partitionString.toInt).toOption

    } yield {
      TopicPartitionOffset(topic, partition, offset)
    }
  }
}
