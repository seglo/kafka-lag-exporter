package com.lightbend.kafka.sparkeventexporter.internal
import java.time.Instant

import com.lightbend.kafka.kafkametricstools.Domain.{Measurements, TopicPartition}
import com.lightbend.kafka.sparkeventexporter.internal.Domain.{Query, SourceMetrics}
import org.apache.spark.sql.streaming.{SourceProgress, StreamingQueryProgress}
import org.json4s.JsonAST.{JInt, JObject}
import org.json4s.jackson.JsonMethods.parseOpt

import scala.util.Try

object SparkEventAdapter {
  /**
    * Parse a `org.apache.spark.sql.streaming.StreamingQueryProgress` for relevant metric labels and values.
    *
    * An example of this JSON could be
    *
    * ```
    * {
    *   "id" : "9a8b3024-d197-4689-9075-43c312e33637",
    *   "runId" : "1b88089d-b62a-4194-89db-2e22708c0fad",
    *   "name" : null,
    *   "timestamp" : "2019-02-04T18:22:08.230Z",
    *   "batchId" : 1,
    *   "numInputRows" : 1832,
    *   "inputRowsPerSecond" : 48.09156297579671,
    *   "processedRowsPerSecond" : 184.9384211588936,
    *   "durationMs" : {
    *     "addBatch" : 9291,
    *     "getBatch" : 3,
    *     "getEndOffset" : 1,
    *     "queryPlanning" : 391,
    *     "setOffsetRange" : 17,
    *     "triggerExecution" : 9906,
    *     "walCommit" : 173
    *   },
    *   "eventTime" : {
    *     "avg" : "2019-02-04T18:21:52.239Z",
    *     "max" : "2019-02-04T18:22:07.000Z",
    *     "min" : "2019-02-04T18:21:38.000Z",
    *     "watermark" : "2019-02-04T18:20:38.000Z"
    *   },
    *   "stateOperators" : [ {
    *     "numRowsTotal" : 3,
    *     "numRowsUpdated" : 2,
    *     "memoryUsedBytes" : 113431,
    *     "customMetrics" : {
    *       "loadedMapCacheHitCount" : 400,
    *       "loadedMapCacheMissCount" : 0,
    *       "stateOnCurrentVersionSizeBytes" : 22047
    *     }
    *   } ],
    *   "sources" : [ {
    *     "description" : "KafkaV2[Subscribe[call-record-pipeline-seglo.cdr-validator.out-1]]",
    *     "startOffset" : {
    *       "call-record-pipeline-seglo.cdr-validator.out-1" : {
    *         "0" : 12382,
    *         "1" : 12126,
    *       }
    *     },
    *     "endOffset" : {
    *       "call-record-pipeline-seglo.cdr-validator.out-1" : {
    *         "0" : 12428,
    *         "1" : 12156,
    *       }
    *     },
    *     "numInputRows" : 1832,
    *     "inputRowsPerSecond" : 48.09156297579671,
    *     "processedRowsPerSecond" : 184.9384211588936
    *   } ],
    *   "sink" : {
    *     "description" : "org.apache.spark.sql.kafka010.KafkaSourceProvider@6598181e"
    *   }
    * ```
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
