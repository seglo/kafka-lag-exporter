package com.lightbend.kafkalagexporter

import akka.actor.testkit.typed.scaladsl.{BehaviorTestKit, TestInbox}
import com.lightbend.kafkalagexporter.Domain._
import com.lightbend.kafkalagexporter.KafkaClient.KafkaClientContract
import org.scalatest.{Matchers, _}

import scala.concurrent.Future
import scala.concurrent.duration._

class ConsumerGroupCollectorSpec extends FreeSpec with Matchers {
  val client = new KafkaClientTest
  val config = ConsumerGroupCollector.CollectorConfig(0 seconds, "default", "")

  val topicPartition0 = TopicPartition("test-topic", 0)
  val topicPartition1 = TopicPartition("test-topic", 1)
  val topicPartition2 = TopicPartition("test-topic", 2)
  val consumerGroupMember0 = ConsumerGroupMember("testClientId", "testConsumerId", "/127.0.0.1", Set(topicPartition0))
  val consumerGroupMember1 = ConsumerGroupMember("testClientId", "testConsumerId", "/127.0.0.2", Set(topicPartition1))
  val consumerGroupMember2 = ConsumerGroupMember("testClientId", "testConsumerId", "/127.0.0.3", Set(topicPartition2))
  val consumerGroupSingleMember = ConsumerGroup("testGroupId", isSimpleGroup = true, "Stable", List(consumerGroupMember0))
  val consumerGroupThreeMember = ConsumerGroup("testGroupId", isSimpleGroup = true, "Stable", List(consumerGroupMember0, consumerGroupMember1, consumerGroupMember2))
  val gtpSingleMember = GroupTopicPartition(consumerGroupSingleMember, topicPartition0)
  val gtp0 = GroupTopicPartition(consumerGroupThreeMember, topicPartition0)
  val gtp1 = GroupTopicPartition(consumerGroupThreeMember, topicPartition1)
  val gtp2 = GroupTopicPartition(consumerGroupThreeMember, topicPartition2)

  "ConsumerGroupCollector should send" - {
    val reporter = TestInbox[LagReporter.Message]()

    val state = ConsumerGroupCollector.CollectorState(
      latestOffsets = Domain.LatestOffsets() + (topicPartition0 -> Measurements.Single(offset = 100, timestamp = 100)),
      lastGroupOffsets = Domain.LastCommittedOffsets() + (gtpSingleMember -> Measurements.Single(offset = 90, timestamp = 100))
    )

    val behavior = ConsumerGroupCollector.collector(config, client, reporter.ref, state)
    val testKit = BehaviorTestKit(behavior)

    val newLatestOffsets = Domain.LatestOffsets() + (topicPartition0 -> Measurements.Single(offset = 200, timestamp = 200))
    val newLastCommittedOffsets = Domain.LastCommittedOffsets() + (gtpSingleMember -> Measurements.Single(offset = 180, timestamp = 200))

    testKit.run(ConsumerGroupCollector.NewOffsets(newLatestOffsets, newLastCommittedOffsets))

    val metrics = reporter.receiveAll()

    "latest offset metric" in {
      metrics should contain(LagReporter.LatestOffsetMetric(config.clusterName, topicPartition0, offset = 200))
    }

    "last group offset metric" in {
      metrics should contain(LagReporter.LastGroupOffsetMetric(config.clusterName, gtpSingleMember, consumerGroupMember0, offset = 180))
    }

    "offset lag metric" in {
      metrics should contain(LagReporter.OffsetLagMetric(config.clusterName, gtpSingleMember, consumerGroupMember0, lag = 20))
    }

    "time lag metric" in {
      metrics should contain(LagReporter.TimeLagMetric(config.clusterName, gtpSingleMember, consumerGroupMember0, 22 milliseconds))
    }

    "max group offset lag metric" in {
      metrics should contain(LagReporter.MaxGroupOffsetLagMetric(config.clusterName, consumerGroupSingleMember, lag = 20))
    }

    "max group time lag metric" in {
      metrics should contain(LagReporter.MaxGroupTimeLagMetric(config.clusterName, consumerGroupSingleMember, 22 milliseconds))
    }
  }

  "ConsumerGroupCollector should calculate max group metrics and send" - {
    val reporter = TestInbox[LagReporter.Message]()

    val state = ConsumerGroupCollector.CollectorState(
      latestOffsets = Domain.LatestOffsets() ++ List(
        topicPartition0 -> Measurements.Single(offset = 100, timestamp = 100),
        topicPartition1 -> Measurements.Single(offset = 100, timestamp = 100),
        topicPartition2 -> Measurements.Single(offset = 100, timestamp = 100)
      ),
      lastGroupOffsets = Domain.LastCommittedOffsets() ++ List(
        gtp0 -> Measurements.Single(offset = 90, timestamp = 100),
        gtp1 -> Measurements.Single(offset = 90, timestamp = 100),
        gtp2 -> Measurements.Single(offset = 90, timestamp = 100),
      )
    )

    val behavior = ConsumerGroupCollector.collector(config, client, reporter.ref, state)
    val testKit = BehaviorTestKit(behavior)

    val newLatestOffsets = Domain.LatestOffsets() ++ List(
      topicPartition0 -> Measurements.Single(offset = 200, timestamp = 200),
      topicPartition1 -> Measurements.Single(offset = 200, timestamp = 200),
      topicPartition2 -> Measurements.Single(offset = 200, timestamp = 200)
    )
    val newLastCommittedOffsets = Domain.LastCommittedOffsets() ++ List(
      gtp0 -> Measurements.Single(offset = 180, timestamp = 200),
      gtp1 -> Measurements.Single(offset = 100, timestamp = 200),
      gtp2 -> Measurements.Single(offset = 180, timestamp = 200),
    )

    testKit.run(ConsumerGroupCollector.NewOffsets(newLatestOffsets, newLastCommittedOffsets))

    val metrics = reporter.receiveAll()

    "max group offset lag metric" in {
      metrics should contain(LagReporter.MaxGroupOffsetLagMetric(config.clusterName, consumerGroupThreeMember, lag = 100))
    }

    "max group time lag metric" in {
      metrics should contain(LagReporter.MaxGroupTimeLagMetric(config.clusterName, consumerGroupThreeMember, 1000 milliseconds))
    }
  }

  // Stub.  No methods actually called.
  class KafkaClientTest extends KafkaClientContract {
    override def getGroups(): Future[List[Domain.ConsumerGroup]] = ???
    override def getLatestOffsets(groups: List[Domain.ConsumerGroup]): Future[Map[Domain.TopicPartition, Measurements.Single]] = ???
    override def getGroupOffsets(groups: List[Domain.ConsumerGroup]): Future[Map[Domain.GroupTopicPartition, Measurements.Measurement]] = ???
    override def close(): Unit = ???
  }

}
