package com.lightbend.kafkaclientmetrics

import com.lightbend.kafkaclientmetrics.Domain.{ConsumerGroup, ConsumerGroupMember, GroupTopicPartition, TopicPartition}

trait TestData {
  val topicPartition0 = TopicPartition("test-topic", 0)
  val topicPartition1 = TopicPartition("test-topic", 1)
  val topicPartition2 = TopicPartition("test-topic", 2)
  val consumerGroupMember0 = ConsumerGroupMember("testClientId", "testConsumerId", "/127.0.0.1", Set(topicPartition0))
  val consumerGroupMember1 = ConsumerGroupMember("testClientId", "testConsumerId", "/127.0.0.2", Set(topicPartition1))
  val consumerGroupMember2 = ConsumerGroupMember("testClientId", "testConsumerId", "/127.0.0.3", Set(topicPartition2))
  val groupId = "testGroupId"
  val consumerGroupSingleMember = ConsumerGroup(groupId, isSimpleGroup = true, "Stable", List(consumerGroupMember0))
  val consumerGroupThreeMember = ConsumerGroup(groupId, isSimpleGroup = true, "Stable", List(consumerGroupMember0, consumerGroupMember1, consumerGroupMember2))
  val gtpSingleMember = GroupTopicPartition(consumerGroupSingleMember, topicPartition0)
  val gtp0 = GroupTopicPartition(consumerGroupThreeMember, topicPartition0)
  val gtp1 = GroupTopicPartition(consumerGroupThreeMember, topicPartition1)
  val gtp2 = GroupTopicPartition(consumerGroupThreeMember, topicPartition2)
}
