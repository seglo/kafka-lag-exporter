/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import com.lightbend.kafkalagexporter.Domain._

trait TestData {
  val cluster = KafkaCluster("default", "brokers:9092")
  val groupId = "testGroupId"
  val clientId = "testClientId"
  val consumerId = "testConsumerId"
  val topic = "test-topic"
  val topicPartition0 = TopicPartition(topic, 0)
  val topicPartition1 = TopicPartition(topic, 1)
  val topicPartition2 = TopicPartition(topic, 2)
  val gtpSingleMember = GroupTopicPartition(groupId, clientId, consumerId, "/127.0.0.1", topicPartition0.topic, topicPartition0.partition)
  val gtp0 = GroupTopicPartition(groupId, clientId, consumerId, "/127.0.0.1", topicPartition0.topic, topicPartition0.partition)
  val gtp1 = GroupTopicPartition(groupId, clientId, consumerId, "/127.0.0.2", topicPartition1.topic, topicPartition1.partition)
  val gtp2 = GroupTopicPartition(groupId, clientId, consumerId, "/127.0.0.3", topicPartition2.topic, topicPartition2.partition)
  val lookupTableOnePoint = LookupTable.Table(20)
  lookupTableOnePoint.addPoint(LookupTable.Point(100, 100))
}
