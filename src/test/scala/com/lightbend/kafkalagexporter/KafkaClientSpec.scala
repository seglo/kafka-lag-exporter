/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import java.util.Optional

import com.lightbend.kafkalagexporter.Domain.GroupOffsets
import com.lightbend.kafkalagexporter.KafkaClient.KafkaTopicPartitionOps
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.mockito.MockitoSugar
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FreeSpec, Matchers}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.concurrent.duration.FiniteDuration

class KafkaClientSpec extends FreeSpec with Matchers with TestData with MockitoSugar with ScalaFutures {
  "KafkaClient" - {
    implicit val ec: ExecutionContextExecutor = ExecutionContext.global

    "getGroupOffsets returns None offsets for missing partitions and doesn't overwrite results for shared topic partitions" in {
      val groupId0 = "testGroupId0"
      val groupId1 = "testGroupId1"

      val groups = List(groupId0, groupId1)

      val gtp0_0 = gtp0.copy(id = groupId0)
      val gtp1_0 = gtp1.copy(id = groupId0)
      val gtp2_0 = gtp2.copy(id = groupId0)
      val gtp0_1 = gtp0.copy(id = groupId1)

      val gtps = List(gtp0_0, gtp1_0, gtp2_0, gtp0_1)

      val client = spy(new KafkaClient(cluster, groupId, FiniteDuration(0, "ms")))

      val groupId0Results = Future.successful(Map(
        topicPartition0.asKafka -> new OffsetAndMetadata(0, Optional.empty(), "")
        // missing topicPartition1
        // missing topicPartition2
      ).asJava)
      doReturn(groupId0Results).when(client).getListConsumerGroupOffsets(groupId0)

      val groupId1Results = Future.successful(Map(
        topicPartition0.asKafka -> new OffsetAndMetadata(1, Optional.empty(), "")
      ).asJava)

      doReturn(groupId1Results).when(client).getListConsumerGroupOffsets(groupId1)


      val groupOffsets = client.getGroupOffsets(0, groups, gtps).futureValue

      groupOffsets.size shouldEqual 4
      groupOffsets(gtp0_0) shouldEqual Some(LookupTable.Point(0, 0))
      groupOffsets(gtp1_0) shouldEqual None // missing partition
      groupOffsets(gtp2_0) shouldEqual None // missing partition
      groupOffsets(gtp0_1) shouldEqual Some(LookupTable.Point(1, 0))

    }

    "getGroupOffsets returns distinct offsets when multiple groups subscribe to same partitions" in {
      val groupId0 = "testGroupId0"
      val groupId1 = "testGroupId1"

      val groups = List(groupId0, groupId1)

      val gtp0_0 = gtp0.copy(id = groupId0)
      val gtp0_1 = gtp0.copy(id = groupId1)

      val gtps = List(gtp0_0, gtp0_1)

      val client = spy(new KafkaClient(cluster, groupId, FiniteDuration(0, "ms")))

      val groupId0Results = Future.successful(Map(
        topicPartition0.asKafka -> new OffsetAndMetadata(0, Optional.empty(), "")
      ).asJava)
      doReturn(groupId0Results).when(client).getListConsumerGroupOffsets(groupId0)

      val groupId1Results = Future.successful(Map(
        topicPartition0.asKafka -> new OffsetAndMetadata(1, Optional.empty(), "")
      ).asJava)
      doReturn(groupId1Results).when(client).getListConsumerGroupOffsets(groupId1)

      val groupOffsets = client.getGroupOffsets(0, groups, gtps).futureValue

      groupOffsets(gtp0_0) shouldEqual Some(LookupTable.Point(0, 0))
      groupOffsets(gtp0_1) shouldEqual Some(LookupTable.Point(1, 0))
    }

    "getOffsetOrZero returns offsets of None (Option[Point]) for missing partitions" in {
      implicit val ec = ExecutionContext.global
      val client = new KafkaClient(cluster, groupId, FiniteDuration(0, "ms"))

      // create offsetMap with missing partition 2
      val offsetMap = GroupOffsets(
        gtp0 -> Some(LookupTable.Point(0, 0)),
        gtp1 -> Some(LookupTable.Point(0, 0))
      )

      val groupOffsets = client.getOffsetOrZero(List(gtp0, gtp1, gtp2), offsetMap)

      groupOffsets.size shouldEqual 3
      groupOffsets(gtp2) shouldEqual None
    }
  }
}
