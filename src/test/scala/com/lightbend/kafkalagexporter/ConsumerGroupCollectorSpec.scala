/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import java.time.{Clock, Instant, ZoneId}

import akka.actor.testkit.typed.scaladsl.{BehaviorTestKit, TestInbox}
import com.lightbend.kafkalagexporter.KafkaClient.KafkaClientContract
import com.lightbend.kafkalagexporter.LookupTable._
import com.lightbend.kafkalagexporter.Domain._
import com.lightbend.kafkalagexporter.Metrics._
import org.mockito.MockitoSugar
import org.scalatest.{Matchers, _}

import scala.concurrent.duration._

class ConsumerGroupCollectorSpec extends FreeSpec with Matchers with TestData with MockitoSugar {
  val client: KafkaClientContract = mock[KafkaClientContract]
  val config = ConsumerGroupCollector.CollectorConfig(0 seconds, 20, KafkaCluster("default", ""), Clock.fixed(Instant.ofEpochMilli(0), ZoneId.systemDefault()))
  val clusterName = config.cluster.name

  val timestampNow = 200

  "ConsumerGroupCollector should send" - {
    val reporter = TestInbox[MetricsSink.Message]()

    val lookupTable = Table(20)
    lookupTable.addPoint(Point(100, 100))

    val state = ConsumerGroupCollector.CollectorState(
      topicPartitionTables = TopicPartitionTable(config.lookupTableSize, Map(topicPartition0 -> lookupTable))
    )

    val behavior = ConsumerGroupCollector.collector(config, client, reporter.ref, state)
    val testKit = BehaviorTestKit(behavior)

    val newLatestOffsets = PartitionOffsets(topicPartition0 -> Point(offset = 200, time = timestampNow))
    val newLastGroupOffsets = GroupOffsets(gtpSingleMember -> Point(offset = 180, time = timestampNow))

    testKit.run(ConsumerGroupCollector.OffsetsSnapshot(timestamp = timestampNow, List(groupId), newLatestOffsets, newLastGroupOffsets))

    val metrics = reporter.receiveAll()

    "report 6 metrics" in { metrics.length shouldBe 6 }

    "latest offset metric" in {
      metrics should contain(
        Metrics.TopicPartitionValueMessage(LatestOffsetMetric, config.cluster.name, topicPartition0, value = 200))
    }

    "last group offset metric" in {
      metrics should contain(
        GroupPartitionValueMessage(LastGroupOffsetMetric, config.cluster.name, gtpSingleMember, value = 180))
    }

    "offset lag metric" in {
      metrics should contain(GroupPartitionValueMessage(OffsetLagMetric, config.cluster.name, gtpSingleMember, value = 20))
    }

    "time lag metric" in {
      metrics should contain(GroupPartitionValueMessage(TimeLagMetric, config.cluster.name, gtpSingleMember, value = 0.02))
    }

    "max group offset lag metric" in {
      metrics should contain(GroupValueMessage(MaxGroupOffsetLagMetric, config.cluster.name, groupId, value = 20))
    }

    "max group time lag metric" in {
      metrics should contain(GroupValueMessage(MaxGroupTimeLagMetric, config.cluster.name, groupId, value = 0.02))
    }
  }

  "ConsumerGroupCollector should calculate max group metrics and send" - {
    val reporter = TestInbox[MetricsSink.Message]()

    val lookupTable = Table(20)
    lookupTable.addPoint(Point(100, 100))

    val state = ConsumerGroupCollector.CollectorState(
      topicPartitionTables = TopicPartitionTable(config.lookupTableSize, Map(
        topicPartition0 -> lookupTable.copy(),
        topicPartition1 -> lookupTable.copy(),
        topicPartition2 -> lookupTable.copy()
      )),
    )

    val behavior = ConsumerGroupCollector.collector(config, client, reporter.ref, state)
    val testKit = BehaviorTestKit(behavior)

    val newLatestOffsets = PartitionOffsets(
      topicPartition0 -> Point(offset = 200, time = 200),
      topicPartition1 -> Point(offset = 200, time = 200),
      topicPartition2 -> Point(offset = 200, time = 200)
    )
    val newLastGroupOffsets = GroupOffsets(
      gtp0 -> Point(offset = 180, time = 200),
      gtp1 -> Point(offset = 100, time = 200),
      gtp2 -> Point(offset = 180, time = 200),
    )

    testKit.run(ConsumerGroupCollector.OffsetsSnapshot(timestamp = timestampNow, List(groupId), newLatestOffsets, newLastGroupOffsets))

    val metrics = reporter.receiveAll()

    "max group offset lag metric" in {
      metrics should contain(GroupValueMessage(MaxGroupOffsetLagMetric, clusterName, groupId, value = 100))
    }

    "max group time lag metric" in {
      metrics should contain(GroupValueMessage(MaxGroupTimeLagMetric, clusterName, groupId, value = 0.1))
    }
  }

  "ConsumerGroupCollector should evict data when group metadata changes" - {
    val reporter = TestInbox[MetricsSink.Message]()

    val lastTimestamp = timestampNow - 100
    val tpTables = TopicPartitionTable(config.lookupTableSize, Map(topicPartition0 -> lookupTableOnePoint.copy()))
    val state = ConsumerGroupCollector.CollectorState(
      topicPartitionTables = tpTables,
      lastSnapshot = Some(ConsumerGroupCollector.OffsetsSnapshot(
        timestamp = lastTimestamp,
        groups = List(groupId),
        latestOffsets = PartitionOffsets(topicPartition0 -> Point(offset = 200, time = lastTimestamp)),
        lastGroupOffsets = GroupOffsets(gtpSingleMember -> Point(offset = 180, time = lastTimestamp))
      ))
    )

    "remove metric for consumer ids no longer being reported" in {
      val behavior = ConsumerGroupCollector.collector(config, client, reporter.ref, state)
      val testKit = BehaviorTestKit(behavior)

      val newConsumerId = s"$consumerId-new"
      val snapshot = ConsumerGroupCollector.OffsetsSnapshot(
        timestamp = timestampNow,
        groups = List(groupId),
        latestOffsets = PartitionOffsets(topicPartition0 -> Point(offset = 200, time = 200)),
        lastGroupOffsets = GroupOffsets(gtpSingleMember.copy(consumerId = newConsumerId) -> Point(offset = 180, time = 200))
      )

      testKit.run(snapshot)

      val metrics = reporter.receiveAll()

      metrics should contain(GroupPartitionRemoveMetricMessage(LastGroupOffsetMetric, clusterName, gtpSingleMember))
      metrics should contain(GroupPartitionRemoveMetricMessage(OffsetLagMetric, clusterName, gtpSingleMember))
      metrics should contain(GroupPartitionRemoveMetricMessage(TimeLagMetric, clusterName, gtpSingleMember))
    }

    "remove metrics for topic group partitions no longer being reported" in {
      val behavior = ConsumerGroupCollector.collector(config, client, reporter.ref, state)
      val testKit = BehaviorTestKit(behavior)

      val newGroupId = s"$groupId-new"
      val snapshot = ConsumerGroupCollector.OffsetsSnapshot(
        timestamp = timestampNow,
        groups = List(newGroupId),
        latestOffsets = PartitionOffsets(topicPartition0 -> Point(offset = 200, time = 200)),
        lastGroupOffsets = GroupOffsets(gtpSingleMember.copy(id = newGroupId) -> Point(offset = 180, time = 200))
      )

      testKit.run(snapshot)

      val metrics = reporter.receiveAll()

      metrics should contain(GroupPartitionRemoveMetricMessage(LastGroupOffsetMetric, clusterName, gtpSingleMember))
      metrics should contain(GroupPartitionRemoveMetricMessage(OffsetLagMetric, clusterName, gtpSingleMember))
      metrics should contain(GroupPartitionRemoveMetricMessage(TimeLagMetric, clusterName, gtpSingleMember))
      metrics should contain(GroupRemoveMetricMessage(MaxGroupTimeLagMetric, clusterName, groupId))
      metrics should contain(GroupRemoveMetricMessage(MaxGroupOffsetLagMetric, clusterName, groupId))
    }

    "remove metrics for topic partitions no longer being reported" - {
      val behavior = ConsumerGroupCollector.collector(config, client, reporter.ref, state)
      val testKit = BehaviorTestKit(behavior)

      val newGroupId = s"$groupId-new"
      val snapshot = ConsumerGroupCollector.OffsetsSnapshot(
        timestamp = timestampNow,
        groups = List(),
        latestOffsets = PartitionOffsets(),
        lastGroupOffsets = GroupOffsets()
      )

      testKit.run(snapshot)

      val metrics = reporter.receiveAll()

      "topic partition metric removed" in {
        metrics should contain(TopicPartitionRemoveMetricMessage(LatestOffsetMetric, clusterName, topicPartition0))
      }

      "all group-related metrics removed" in {
        metrics should contain(GroupPartitionRemoveMetricMessage(LastGroupOffsetMetric, clusterName, gtpSingleMember))
        metrics should contain(GroupPartitionRemoveMetricMessage(OffsetLagMetric, clusterName, gtpSingleMember))
        metrics should contain(GroupPartitionRemoveMetricMessage(TimeLagMetric, clusterName, gtpSingleMember))
        metrics should contain(GroupRemoveMetricMessage(MaxGroupTimeLagMetric, clusterName, groupId))
        metrics should contain(GroupRemoveMetricMessage(MaxGroupOffsetLagMetric, clusterName, groupId))
      }

      "topic partition in topic partition table removed" in {
        ???
      }

    }
  }
}
