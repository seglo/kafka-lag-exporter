/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter
import java.util.Optional

import com.lightbend.kafkalagexporter.KafkaClient.KafkaTopicPartitionOps
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.scalatest.{FreeSpec, Matchers}

import scala.collection.immutable.Map
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

class KafkaClientSpec extends FreeSpec with Matchers with TestData {
  "KafkaClient" - {
    "get group offsets" in {
      implicit val ec = ExecutionContext.global
      val offset = new OffsetAndMetadata(1, Optional.empty(), "")
      val client = new KafkaClient(cluster, groupId, FiniteDuration(0, "ms"))

      // create offsetMap with missing partition 2
      val offsetMap = Map(
        topicPartition0.asKafka -> offset,
        topicPartition1.asKafka -> offset
      )
      val groupOffsets = client.actualGroupOffsets(0, List(gtp0, gtp1, gtp2), offsetMap)

      groupOffsets(gtp2) shouldEqual LookupTable.Point(0, 0)
    }
  }

}
