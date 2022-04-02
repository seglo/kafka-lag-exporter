/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2022 Sean Glover <https://seanglover.com>
 */

package com.lightbend.kafkalagexporter

import java.util.Optional

import com.lightbend.kafkalagexporter.Domain.GroupOffsets
import com.lightbend.kafkalagexporter.KafkaClient.{AdminKafkaClientContract, ConsumerKafkaClientContract, KafkaTopicPartitionOps}
import org.apache.kafka.clients.admin._
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.{ConsumerGroupState, TopicPartition => KafkaTopicPartition}
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

class KafkaClientSpec extends AnyFreeSpec with Matchers with TestData with MockitoSugar with ArgumentMatchersSugar with ScalaFutures {
  "KafkaClient" - {
    "getGroupOffsets" - {
      "returns None offsets for missing partitions and doesn't overwrite results for shared topic partitions" in {
        val groupId0 = "testGroupId0"
        val groupId1 = "testGroupId1"

        val groups = List(groupId0, groupId1)

        val gtp0_0 = gtp0.copy(id = groupId0)
        val gtp1_0 = gtp1.copy(id = groupId0)
        val gtp2_0 = gtp2.copy(id = groupId0)
        val gtp0_1 = gtp0.copy(id = groupId1)

        val gtps = List(gtp0_0, gtp1_0, gtp2_0, gtp0_1)

        val (adminKafkaClient, _, client) = getClient()

        val groupId0Results = Future.successful(Map(
          topicPartition0.asKafka -> new OffsetAndMetadata(0, Optional.empty(), "")
          // missing topicPartition1
          // missing topicPartition2
        ).asJava)
        when(adminKafkaClient.listConsumerGroupOffsets(groupId0)).thenReturn(groupId0Results)

        val groupId1Results = Future.successful(Map(
          topicPartition0.asKafka -> new OffsetAndMetadata(1, Optional.empty(), "")
        ).asJava)
        when(adminKafkaClient.listConsumerGroupOffsets(groupId1)).thenReturn(groupId1Results)

        val groupOffsets = client.getGroupOffsets(0, groups, gtps).futureValue

        groupOffsets.size shouldEqual 4
        groupOffsets(gtp0_0) shouldEqual Some(LookupTable.Point(0, 0))
        groupOffsets(gtp1_0) shouldEqual None // missing partition
        groupOffsets(gtp2_0) shouldEqual None // missing partition
        groupOffsets(gtp0_1) shouldEqual Some(LookupTable.Point(1, 0))

      }

      "returns distinct offsets when multiple groups subscribe to same partitions" in {
        val groupId0 = "testGroupId0"
        val groupId1 = "testGroupId1"

        val groups = List(groupId0, groupId1)

        val gtp0_0 = gtp0.copy(id = groupId0)
        val gtp0_1 = gtp0.copy(id = groupId1)

        val gtps = List(gtp0_0, gtp0_1)

        val (adminKafkaClient, _, client) = getClient()

        val groupId0Results = Future.successful(Map(
          topicPartition0.asKafka -> new OffsetAndMetadata(0, Optional.empty(), "")
        ).asJava)
        when(adminKafkaClient.listConsumerGroupOffsets(groupId0)).thenReturn(groupId0Results)

        val groupId1Results = Future.successful(Map(
          topicPartition0.asKafka -> new OffsetAndMetadata(1, Optional.empty(), "")
        ).asJava)
        when(adminKafkaClient.listConsumerGroupOffsets(groupId1)).thenReturn(groupId1Results)

        val groupOffsets = client.getGroupOffsets(0, groups, gtps).futureValue

        groupOffsets(gtp0_0) shouldEqual Some(LookupTable.Point(0, 0))
        groupOffsets(gtp0_1) shouldEqual Some(LookupTable.Point(1, 0))
      }

      "returns no offsets when a null offset is returned" in {
        val groups = List(groupId)
        val gtps = List(gtp0)

        val (adminKafkaClient, _, client) = getClient()

        val listGroupOffsetResults = Future.successful(Map[KafkaTopicPartition, OffsetAndMetadata](
          topicPartition0.asKafka -> null
        ).asJava)
        when(adminKafkaClient.listConsumerGroupOffsets(groupId)).thenReturn(listGroupOffsetResults)

        val groupOffsets = client.getGroupOffsets(0, groups, gtps).futureValue

        groupOffsets(gtp0) shouldEqual None
      }
    }

    "getOffsetOrZero returns offsets of None (Option[Point]) for missing partitions" in {
      val (_,_,client) = getClient()

      // create offsetMap with missing partition 2
      val offsetMap = GroupOffsets(
        gtp0 -> Some(LookupTable.Point(0, 0)),
        gtp1 -> Some(LookupTable.Point(0, 0))
      )

      val groupOffsets = client.getOffsetOrZero(List(gtp0, gtp1, gtp2), offsetMap)

      groupOffsets.size shouldEqual 3
      groupOffsets(gtp2) shouldEqual None
    }

    "groupTopicPartition" - {
      val tmpHost = "brokers"
      "will default to fetching all topics" in {
        val (_,_,client) = getClient()
        val tps = Set(topicPartition0, topicPartition1, topicPartition2, topic2Partition0).map(tp => new KafkaTopicPartition(tp.topic, tp.partition)).asJava
        val members = List(new MemberDescription(consumerId, clientId, tmpHost, new MemberAssignment(tps))).asJava

        val description = new ConsumerGroupDescription(groupId, true, members, "", ConsumerGroupState.STABLE, node)

        client.groupTopicPartitions(groupId, description) should contain theSameElementsAs List(gtp0, gtp1, gtp2, gt2p0).map(gtp => gtp.copy(host = tmpHost))
      }

      "will only fetch whitelisted topic when whitelist contains a single topic" in {
        val tmpCluster = cluster.copy(topicWhitelist = List(topic2))
        val (_,_,client) = getClient(tmpCluster)
        val tps = Set(topicPartition0, topicPartition1, topicPartition2, topic2Partition0).map(tp => new KafkaTopicPartition(tp.topic, tp.partition)).asJava
        val members = List(new MemberDescription(consumerId, clientId, tmpHost, new MemberAssignment(tps))).asJava

        val description = new ConsumerGroupDescription(groupId, true, members, "", ConsumerGroupState.STABLE, node)

        client.groupTopicPartitions(groupId, description) should contain theSameElementsAs List(gt2p0).map(gtp => gtp.copy(host = tmpHost))
      }

      "will only fetch non blacklisted topics" in {
        val tmpCluster: KafkaCluster = cluster.copy(topicWhitelist = List(topic, topic2), topicBlacklist = List(topic))
        val (_,_,client) = getClient(tmpCluster)
        val tps = Set(topicPartition0, topicPartition1, topicPartition2, topic2Partition0).map(tp => new KafkaTopicPartition(tp.topic, tp.partition)).asJava
        val members = List(new MemberDescription(consumerId, clientId, tmpHost, new MemberAssignment(tps))).asJava

        val description = new ConsumerGroupDescription(groupId, true, members, "", ConsumerGroupState.STABLE, node)

        client.groupTopicPartitions(groupId, description) should contain theSameElementsAs List(gt2p0).map(gtp => gtp.copy(host = tmpHost))
      }

      "will only fetch whitelisted topics when whitelist is a regex against multiple topics" in {
        val tmpCluster = cluster.copy(topicWhitelist = List("test.+"))
        val (_,_,client) = getClient(tmpCluster)
        val tps = Set(topicPartition0, topicPartition1, topicPartition2, topic2Partition0).map(tp => new KafkaTopicPartition(tp.topic, tp.partition)).asJava
        val members = List(new MemberDescription(consumerId, clientId, tmpHost, new MemberAssignment(tps))).asJava

        val description = new ConsumerGroupDescription(groupId, true, members, "", ConsumerGroupState.STABLE, node)

        client.groupTopicPartitions(groupId, description) should contain theSameElementsAs List(gtp0, gtp1, gtp2, gt2p0).map(gtp => gtp.copy(host = tmpHost))
      }

      "will exclude topics that match with blacklist regex" in {
        val tmpCluster = cluster.copy(topicWhitelist = List("test.+"), topicBlacklist = List(".+topic-2"))
        val (_,_,client) = getClient(tmpCluster)
        val tps = Set(topicPartition0, topicPartition1, topicPartition2, topic2Partition0).map(tp => new KafkaTopicPartition(tp.topic, tp.partition)).asJava
        val members = List(new MemberDescription(consumerId, clientId, tmpHost, new MemberAssignment(tps))).asJava

        val description = new ConsumerGroupDescription(groupId, true, members, "", ConsumerGroupState.STABLE, node)

        client.groupTopicPartitions(groupId, description) should contain theSameElementsAs List(gtp0, gtp1, gtp2).map(gtp => gtp.copy(host = tmpHost))
      }

      "will not return any topics if the whitelist is empty" in {
        val tmpCluster = cluster.copy(topicWhitelist = List.empty)
        val (_,_,client) = getClient(tmpCluster)
        val tps = Set(topicPartition0, topicPartition1, topicPartition2, topic2Partition0).map(tp => new KafkaTopicPartition(tp.topic, tp.partition)).asJava
        val members = List(new MemberDescription(consumerId, clientId, tmpHost, new MemberAssignment(tps))).asJava

        val description = new ConsumerGroupDescription(groupId, true, members, "", ConsumerGroupState.STABLE, node)

        client.groupTopicPartitions(groupId, description) shouldBe empty
      }
    }

    "getGroupIds" - {
      val groupId1 = "testGroupId1"
      val groupId2 = "testGroupId2"
      val groupIdIotData = "iot-data"

      val groups = List(
        new ConsumerGroupListing(groupId1, false),
        new ConsumerGroupListing(groupId2, false),
        new ConsumerGroupListing(groupIdIotData, false)
      ).asJavaCollection

      "will default to fetching all group IDs" in {
        val tmpCluster = cluster
        val (_,_,client) = getClient(tmpCluster)
        val groupIds = client.getGroupIds(groups)
        groupIds should contain theSameElementsAs List(groupId1, groupId2, groupIdIotData)
      }

      "will only return one group when whitelist contains a single group" in {
        val tmpCluster = cluster.copy(groupWhitelist = List(groupId1))
        val (_,_,client) = getClient(tmpCluster)
        val groupIds = client.getGroupIds(groups)
        groupIds should contain theSameElementsAs List(groupId1)
      }

      "will only return groups for whitelist that contains a regex that matches multiple groups" in {
        val tmpCluster = cluster.copy(groupWhitelist = List("testGroupId[0-9]"))
        val (_,_,client) = getClient(tmpCluster)
        val groupIds = client.getGroupIds(groups)
        groupIds should contain theSameElementsAs List(groupId1, groupId2)
      }

      "will not return any groups when whitelist is empty" in {
        val tmpCluster = cluster.copy(groupWhitelist = List.empty)
        val (_,_,client) = getClient(tmpCluster)
        val groupIds = client.getGroupIds(groups)
        groupIds shouldBe List.empty
      }

      "will only fetch non blacklisted groups" in {
        val tmpCluster = cluster.copy(groupBlacklist = List(groupIdIotData))
        val (_,_,client) = getClient(tmpCluster)
        val groupIds = client.getGroupIds(groups)
        groupIds should contain theSameElementsAs List(groupId1, groupId2)
      }

      "will exclude groups that match with blacklist regex" in {
        val tmpCluster = cluster.copy(groupBlacklist = List("testGroupId[0-9]"))
        val (_,_,client) = getClient(tmpCluster)
        val groupIds = client.getGroupIds(groups)
        groupIds should contain theSameElementsAs List(groupIdIotData)
      }

      "will not return any groups when blacklist matches all" in {
        val tmpCluster = cluster.copy(groupBlacklist = List(".*"))
        val (_,_,client) = getClient(tmpCluster)
        val groupIds = client.getGroupIds(groups)
        groupIds shouldBe List.empty
      }

      "will only fetch whitelisted and non blacklisted groups" in {
        val tmpCluster = cluster.copy(groupWhitelist = List(groupId1, groupId2), groupBlacklist = List(groupId2, groupIdIotData))
        val (_,_,client) = getClient(tmpCluster)
        val groupIds = client.getGroupIds(groups)
        groupIds should contain theSameElementsAs List(groupId1)
      }

      "will include groups that match with whitelist regex and exclude groups that match with blacklist regex" in {
        val tmpCluster = cluster.copy(groupWhitelist = List("iot-.+"), groupBlacklist = List("testGroupId[0-9]"))
        val (_,_,client) = getClient(tmpCluster)
        val groupIds = client.getGroupIds(groups)
        groupIds should contain theSameElementsAs List(groupIdIotData)
      }
    }
  }

  def getClient(cluster: KafkaCluster = cluster, groupId: String = groupId) = {
    implicit val ec: ExecutionContextExecutor = ExecutionContext.global
    val adminKafkaClient = mock[AdminKafkaClientContract]
    val consumer = mock[ConsumerKafkaClientContract]
    val client = spy(new KafkaClient(cluster, consumer, adminKafkaClient))
    (adminKafkaClient, consumer, client)
  }
}
