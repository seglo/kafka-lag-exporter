package com.lightbend.kafkalagexporter

import akka.actor.testkit.typed.scaladsl.{BehaviorTestKit, TestInbox}
import com.lightbend.kafkalagexporter.Domain._
import com.lightbend.kafkalagexporter.KafkaClient.KafkaClientContract
import org.mockito.MockitoSugar
import org.scalatest.{Matchers, _}

import scala.concurrent.duration._

class ConsumerGroupCollectorSpec extends FreeSpec with Matchers with TestData with MockitoSugar {
  val client: KafkaClientContract = mock[KafkaClientContract]

  "ConsumerGroupCollector should send" - {
    val reporter = TestInbox[LagReporter.Message]()

    val state = ConsumerGroupCollector.CollectorState(
      latestOffsets = Domain.LatestOffsets() + (topicPartition0 -> Measurements.Single(offset = 100, timestamp = 100)),
      lastGroupOffsets = Domain.LastGroupOffsets() + (gtpSingleMember -> Measurements.Single(offset = 90, timestamp = 100))
    )

    val behavior = ConsumerGroupCollector.collector(config, client, reporter.ref, state)
    val testKit = BehaviorTestKit(behavior)

    val newLatestOffsets = Domain.LatestOffsets() + (topicPartition0 -> Measurements.Single(offset = 200, timestamp = 200))
    val newLastGroupOffsets = Domain.LastGroupOffsets() + (gtpSingleMember -> Measurements.Single(offset = 180, timestamp = 200))

    testKit.run(ConsumerGroupCollector.NewOffsets(timestamp = 0, List(consumerGroupSingleMember), newLatestOffsets, newLastGroupOffsets))

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
      lastGroupOffsets = Domain.LastGroupOffsets() ++ List(
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
    val newLastGroupOffsets = Domain.LastGroupOffsets() ++ List(
      gtp0 -> Measurements.Single(offset = 180, timestamp = 200),
      gtp1 -> Measurements.Single(offset = 100, timestamp = 200),
      gtp2 -> Measurements.Single(offset = 180, timestamp = 200),
    )

    testKit.run(ConsumerGroupCollector.NewOffsets(timestamp = 0, List(consumerGroupThreeMember), newLatestOffsets, newLastGroupOffsets))

    val metrics = reporter.receiveAll()

    "max group offset lag metric" in {
      metrics should contain(LagReporter.MaxGroupOffsetLagMetric(config.clusterName, consumerGroupThreeMember, lag = 100))
    }

    "max group time lag metric" in {
      metrics should contain(LagReporter.MaxGroupTimeLagMetric(config.clusterName, consumerGroupThreeMember, 1000 milliseconds))
    }
  }

  "ConsumerGroupCollector when consumer group partitions have no offset should send" - {
    val reporter = TestInbox[LagReporter.Message]()

    val state = ConsumerGroupCollector.CollectorState(
      latestOffsets = Domain.LatestOffsets() + (topicPartition0 -> Measurements.Single(offset = 100, timestamp = 100)),
      lastGroupOffsets = Domain.LastGroupOffsets() + (gtpSingleMember -> Measurements.Single(offset = 0, timestamp = 100))
    )

    val behavior = ConsumerGroupCollector.collector(config, client, reporter.ref, state)
    val testKit = BehaviorTestKit(behavior)

    val newLatestOffsets = Domain.LatestOffsets() + (topicPartition0 -> Measurements.Single(offset = 200, timestamp = 200))
    val newLastGroupOffsets = Domain.LastGroupOffsets() // <-- no new group offsets

    testKit.run(ConsumerGroupCollector.NewOffsets(timestamp = 0, List(consumerGroupSingleMember), newLatestOffsets, newLastGroupOffsets))

    val metrics = reporter.receiveAll()

    "latest offset metric" in {
      metrics should contain(LagReporter.LatestOffsetMetric(config.clusterName, topicPartition0, offset = 200))
    }

    "last group offset metric" in {
      metrics should contain(LagReporter.LastGroupOffsetMetric(config.clusterName, gtpSingleMember, consumerGroupMember0, offset = 0))
    }

    "offset lag metric" in {
      metrics should contain(LagReporter.OffsetLagMetric(config.clusterName, gtpSingleMember, consumerGroupMember0, lag = 200))
    }

    "time lag metric" in {
      metrics should contain(LagReporter.TimeLagMetric(config.clusterName, gtpSingleMember, consumerGroupMember0, 0 milliseconds))
    }

    "max group offset lag metric" in {
      metrics should contain(LagReporter.MaxGroupOffsetLagMetric(config.clusterName, consumerGroupSingleMember, lag = 200))
    }

    "max group time lag metric" in {
      metrics should contain(LagReporter.MaxGroupTimeLagMetric(config.clusterName, consumerGroupSingleMember, 0 milliseconds))
    }
  }
}
