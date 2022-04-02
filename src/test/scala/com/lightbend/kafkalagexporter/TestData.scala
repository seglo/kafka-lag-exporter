/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2022 Sean Glover <https://seanglover.com>
 */

package com.lightbend.kafkalagexporter

import com.lightbend.kafkalagexporter.Domain._

import org.apache.kafka.common.Node

trait TestData {
  val cluster = KafkaCluster("default", "brokers:9092")
  val node = new Node(1001, "brokers", 9092)
  val groupId = "testGroupId"
  val clientId = "testClientId"
  val consumerId = "testConsumerId"
  val topic = "test-topic"
  val topic2 = "test-topic-2"
  val topicPartition0 = TopicPartition(topic, 0)
  val topicPartition1 = TopicPartition(topic, 1)
  val topicPartition2 = TopicPartition(topic, 2)
  val topic2Partition0 = TopicPartition(topic2, 0)
  val gtpSingleMember = GroupTopicPartition(groupId, clientId, consumerId, "/127.0.0.1", topicPartition0.topic, topicPartition0.partition)
  val gtp0 = GroupTopicPartition(groupId, clientId, consumerId, "/127.0.0.1", topicPartition0.topic, topicPartition0.partition)
  val gtp1 = GroupTopicPartition(groupId, clientId, consumerId, "/127.0.0.2", topicPartition1.topic, topicPartition1.partition)
  val gtp2 = GroupTopicPartition(groupId, clientId, consumerId, "/127.0.0.3", topicPartition2.topic, topicPartition2.partition)
  val gt2p0 = GroupTopicPartition(groupId, clientId, consumerId, "/127.0.0.4", topic2Partition0.topic, topic2Partition0.partition)
  val lookupTableOnePoint = LookupTable.Table(20)
  lookupTableOnePoint.addPoint(LookupTable.Point(100, 100))
}
