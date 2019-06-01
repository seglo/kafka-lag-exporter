/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import com.lightbend.kafkalagexporter.Domain._

trait TestData {
  val groupId = "testGroupId"
  val clientId = "testClientId"
  val consumerId = "testConsumerId"
  val topic = "test-topic"
  val cluster = KafkaCluster("default", "brokers:9092")
  val topicPartition0 = TopicPartition("test-topic", 0)
  val topicPartition1 = TopicPartition("test-topic", 1)
  val topicPartition2 = TopicPartition("test-topic", 2)
  val consumerGroupMember0 = ConsumerGroupMember("testClientId", "testConsumerId", "/127.0.0.1", Set(topicPartition0))
  val consumerGroupMember1 = ConsumerGroupMember("testClientId", "testConsumerId", "/127.0.0.2", Set(topicPartition1))
  val consumerGroupMember2 = ConsumerGroupMember("testClientId", "testConsumerId", "/127.0.0.3", Set(topicPartition2))
  val consumerGroupSingleMember = ConsumerGroup(groupId, isSimpleGroup = true, "Stable", List(consumerGroupMember0))
  val consumerGroupThreeMember = ConsumerGroup(groupId, isSimpleGroup = true, "Stable", List(consumerGroupMember0, consumerGroupMember1, consumerGroupMember2))
  val gtpSingleMember2 = FlatGroupTopicPartition(groupId, clientId, consumerId, "/127.0.0.1", topic, 0)
  val gtpSingleMember = GroupTopicPartition(consumerGroupSingleMember, topicPartition0)
  val gtp0 = GroupTopicPartition(consumerGroupThreeMember, topicPartition0)
  val gtp1 = GroupTopicPartition(consumerGroupThreeMember, topicPartition1)
  val gtp2 = GroupTopicPartition(consumerGroupThreeMember, topicPartition2)
  val gtp02 = FlatGroupTopicPartition(groupId, clientId, consumerId, "/127.0.0.1", topic, 0)
  val gtp12 = FlatGroupTopicPartition(groupId, clientId, consumerId, "/127.0.0.2", topic, 1)
  val gtp22 = FlatGroupTopicPartition(groupId, clientId, consumerId, "/127.0.0.3", topic, 2)
}
