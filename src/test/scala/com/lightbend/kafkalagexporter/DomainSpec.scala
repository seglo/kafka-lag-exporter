/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter
import org.scalatest.{FreeSpec, Matchers}

import Domain._

class DomainSpec extends FreeSpec with Matchers {
  "ConsumerGroup" - {
    "calculate difference in members" in {
      val topicPartition0 = TopicPartition("test-topic", 0)
      val topicPartition1 = TopicPartition("test-topic", 1)
      val topicPartition2 = TopicPartition("test-topic", 2)
      val consumerGroupMember0 = ConsumerGroupMember("testClientId", "testConsumerId", "/127.0.0.1", Set(topicPartition0))
      val consumerGroupMember1 = ConsumerGroupMember("testClientId", "testConsumerId", "/127.0.0.2", Set(topicPartition1))
      val consumerGroupMember2 = ConsumerGroupMember("testClientId", "testConsumerId", "/127.0.0.3", Set(topicPartition2))
      val groupId = "testGroupId"
      val consumerGroupThreeMember = ConsumerGroup(groupId, isSimpleGroup = true, "Stable", List(consumerGroupMember0, consumerGroupMember1, consumerGroupMember2))

      val newConsumerGroupMember0 = ConsumerGroupMember("testClientId", "testConsumerId", "/127.0.0.1", Set(topicPartition0, topicPartition1, topicPartition2))
      val newConsumerGroupThreeMember = ConsumerGroup(groupId, isSimpleGroup = true, "Stable", List(newConsumerGroupMember0))



    }
  }
}
