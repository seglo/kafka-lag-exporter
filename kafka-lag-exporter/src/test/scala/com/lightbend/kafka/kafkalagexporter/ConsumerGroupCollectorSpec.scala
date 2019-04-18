package com.lightbend.kafka.kafkalagexporter

import java.time.{Clock, Instant, ZoneId}

import akka.actor.testkit.typed.scaladsl.{BehaviorTestKit, TestInbox}
import com.lightbend.kafka.kafkametricstools
import com.lightbend.kafka.kafkametricstools.Domain._
import com.lightbend.kafka.kafkametricstools.KafkaClient.KafkaClientContract
import com.lightbend.kafka.kafkametricstools.{Domain, LookupTable, MetricsSink}
import org.mockito.MockitoSugar
import org.scalatest.{Matchers, _}

import scala.concurrent.duration._

class ConsumerGroupCollectorSpec extends FreeSpec with Matchers with kafkametricstools.TestData with MockitoSugar {
  val client: KafkaClientContract = mock[KafkaClientContract]
  val config = ConsumerGroupCollector.CollectorConfig(0 seconds, "default", "", Clock.fixed(Instant.ofEpochMilli(0), ZoneId.systemDefault()))

  val timestampNow = 200

  "ConsumerGroupCollector should send" - {
    val reporter = TestInbox[MetricsSink.Message]()

    val lookupTable = LookupTable.Table(20)
    lookupTable.addPoint(LookupTable.Point(100, 100))

    // TODO: remove anything from state not used anymore
    val state = ConsumerGroupCollector.CollectorState(
      topicPartitionTables = Domain.TopicPartitionTable(Map(topicPartition0 -> lookupTable))
    )

    val behavior = ConsumerGroupCollector.collector(config, client, reporter.ref, state)
    val testKit = BehaviorTestKit(behavior)

    val newLatestOffsets = Domain.PartitionOffsets() + (topicPartition0 -> Measurements.Single(offset = 200, timestamp = timestampNow))
    val newLastGroupOffsets = Domain.GroupOffsets() + (gtpSingleMember -> Measurements.Single(offset = 180, timestamp = timestampNow))

    testKit.run(ConsumerGroupCollector.NewOffsets(timestamp = timestampNow, List(consumerGroupSingleMember), newLatestOffsets, newLastGroupOffsets))

    val metrics = reporter.receiveAll()

    "report 6 metrics" in { metrics.length shouldBe 6 }

    "latest offset metric" in {
      metrics should contain(
        Metrics.LatestOffsetMetric(config.clusterName, topicPartition0, value = 200))
    }

    "last group offset metric" in {
      metrics should contain(
        Metrics.LastGroupOffsetMetric(config.clusterName, gtpSingleMember, consumerGroupMember0, value = 180))
    }

    "offset lag metric" in {
      metrics should contain(Metrics.OffsetLagMetric(config.clusterName, gtpSingleMember, consumerGroupMember0, value = 20))
    }

    "time lag metric" in {
      metrics should contain(Metrics.TimeLagMetric(config.clusterName, gtpSingleMember, consumerGroupMember0, value = 0.02))
    }

    "max group offset lag metric" in {
      metrics should contain(Metrics.MaxGroupOffsetLagMetric(config.clusterName, consumerGroupSingleMember, value = 20))
    }

    "max group time lag metric" in {
      metrics should contain(Metrics.MaxGroupTimeLagMetric(config.clusterName, consumerGroupSingleMember, value = 0.02))
    }
  }

  "ConsumerGroupCollector should calculate max group metrics and send" - {
    val reporter = TestInbox[MetricsSink.Message]()

    val lookupTable = LookupTable.Table(20)
    lookupTable.addPoint(LookupTable.Point(100, 100))

    val state = ConsumerGroupCollector.CollectorState(
      topicPartitionTables = Domain.TopicPartitionTable(Map(
        topicPartition0 -> lookupTable.copy(),
        topicPartition1 -> lookupTable.copy(),
        topicPartition2 -> lookupTable.copy()
      )),
//      latestOffsets = Domain.PartitionOffsets() ++ List(
//        topicPartition0 -> Measurements.Single(offset = 100, timestamp = 100),
//        topicPartition1 -> Measurements.Single(offset = 100, timestamp = 100),
//        topicPartition2 -> Measurements.Single(offset = 100, timestamp = 100)
//      ),
//      lastGroupOffsets = Domain.GroupOffsets() ++ List(
//        gtp0 -> Measurements.Single(offset = 90, timestamp = 100),
//        gtp1 -> Measurements.Single(offset = 90, timestamp = 100),
//        gtp2 -> Measurements.Single(offset = 90, timestamp = 100),
//      )
    )

    val behavior = ConsumerGroupCollector.collector(config, client, reporter.ref, state)
    val testKit = BehaviorTestKit(behavior)

    val newLatestOffsets = Domain.PartitionOffsets() ++ List(
      topicPartition0 -> Measurements.Single(offset = 200, timestamp = 200),
      topicPartition1 -> Measurements.Single(offset = 200, timestamp = 200),
      topicPartition2 -> Measurements.Single(offset = 200, timestamp = 200)
    )
    val newLastGroupOffsets = Domain.GroupOffsets() ++ List(
      gtp0 -> Measurements.Single(offset = 180, timestamp = 200),
      gtp1 -> Measurements.Single(offset = 100, timestamp = 200),
      gtp2 -> Measurements.Single(offset = 180, timestamp = 200),
    )

    testKit.run(ConsumerGroupCollector.NewOffsets(timestamp = timestampNow, List(consumerGroupThreeMember), newLatestOffsets, newLastGroupOffsets))

    val metrics = reporter.receiveAll()

    "max group offset lag metric" in {
      metrics should contain(Metrics.MaxGroupOffsetLagMetric(config.clusterName, consumerGroupThreeMember, value = 100))
    }

    "max group time lag metric" in {
      metrics should contain(Metrics.MaxGroupTimeLagMetric(config.clusterName, consumerGroupThreeMember, value = 0.1))
    }
  }

  // TODO: debug
  "ConsumerGroupCollector when consumer group partitions have no offset should send" - {
    val reporter = TestInbox[MetricsSink.Message]()

    val state = ConsumerGroupCollector.CollectorState(
      latestOffsets = Domain.PartitionOffsets() + (topicPartition0 -> Measurements.Single(offset = 100, timestamp = 100)),
      lastGroupOffsets = Domain.GroupOffsets() + (gtpSingleMember -> Measurements.Single(offset = 0, timestamp = 100))
    )

    val behavior = ConsumerGroupCollector.collector(config, client, reporter.ref, state)
    val testKit = BehaviorTestKit(behavior)

    val newLatestOffsets = Domain.PartitionOffsets() + (topicPartition0 -> Measurements.Single(offset = 200, timestamp = 200))
    val newLastGroupOffsets = Domain.GroupOffsets() // <-- no new group offsets

    testKit.run(ConsumerGroupCollector.NewOffsets(timestamp = 0, List(consumerGroupSingleMember), newLatestOffsets, newLastGroupOffsets))

    val metrics = reporter.receiveAll()

    "latest offset metric" in {
      metrics should contain(Metrics.LatestOffsetMetric(config.clusterName, topicPartition0, value = 200))
    }

    "last group offset metric" in {
      metrics should contain(Metrics.LastGroupOffsetMetric(config.clusterName, gtpSingleMember, consumerGroupMember0, value = 0))
    }

    "offset lag metric" in {
      metrics should contain(Metrics.OffsetLagMetric(config.clusterName, gtpSingleMember, consumerGroupMember0, value = 200))
    }

    "time lag metric" in {
      metrics should contain(Metrics.TimeLagMetric(config.clusterName, gtpSingleMember, consumerGroupMember0, value = 0))
    }

    "max group offset lag metric" in {
      metrics should contain(Metrics.MaxGroupOffsetLagMetric(config.clusterName, consumerGroupSingleMember, value = 200))
    }

    "max group time lag metric" in {
      metrics should contain(Metrics.MaxGroupTimeLagMetric(config.clusterName, consumerGroupSingleMember, value = 0))
    }
  }
}
