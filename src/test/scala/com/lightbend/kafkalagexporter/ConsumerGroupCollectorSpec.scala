/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import java.time.{Clock, Instant, ZoneId}

import akka.actor.testkit.typed.scaladsl.{BehaviorTestKit, TestInbox}
import com.lightbend.kafkalagexporter.KafkaClient.KafkaClientContract
import com.lightbend.kafkalagexporter.LookupTable._
import org.mockito.MockitoSugar
import org.scalatest.{Matchers, _}

import scala.concurrent.duration._

class ConsumerGroupCollectorSpec extends FreeSpec with Matchers with TestData with MockitoSugar {
  val client: KafkaClientContract = mock[KafkaClientContract]
  val config = ConsumerGroupCollector.CollectorConfig(0 seconds, 20, KafkaCluster("default", ""), Clock.fixed(Instant.ofEpochMilli(0), ZoneId.systemDefault()))

  val timestampNow = 200

  "ConsumerGroupCollector should send" - {
    val reporter = TestInbox[MetricsSink.Message]()

    val lookupTable = LookupTable.Table(20)
    lookupTable.addPoint(LookupTable.Point(100, 100))

    val state = ConsumerGroupCollector.CollectorState(
      topicPartitionTables = Domain.TopicPartitionTable(config.lookupTableSize, Map(topicPartition0 -> lookupTable))
    )

    val behavior = ConsumerGroupCollector.collector(config, client, reporter.ref, state)
    val testKit = BehaviorTestKit(behavior)

    val newLatestOffsets = Domain.PartitionOffsets() + (topicPartition0 -> Point(offset = 200, time = timestampNow))
    val newLastGroupOffsets = Domain.GroupOffsets() + (gtpSingleMember2 -> Point(offset = 180, time = timestampNow))

    testKit.run(ConsumerGroupCollector.OffsetsSnapshot(timestamp = timestampNow, List(groupId), newLatestOffsets, newLastGroupOffsets))

    val metrics = reporter.receiveAll()

    "report 6 metrics" in { metrics.length shouldBe 6 }

    "latest offset metric" in {
      metrics should contain(
        Metrics.TopicPartitionValueMessage(Metrics.LatestOffsetMetric, config.cluster.name, topicPartition0, value = 200))
    }

    "last group offset metric" in {
      metrics should contain(
        Metrics.GroupPartitionValueMessage(Metrics.LastGroupOffsetMetric, config.cluster.name, gtpSingleMember2, value = 180))
    }

    "offset lag metric" in {
      metrics should contain(Metrics.GroupPartitionValueMessage(Metrics.OffsetLagMetric, config.cluster.name, gtpSingleMember2, value = 20))
    }

    "time lag metric" in {
      metrics should contain(Metrics.GroupPartitionValueMessage(Metrics.TimeLagMetric, config.cluster.name, gtpSingleMember2, value = 0.02))
    }

    "max group offset lag metric" in {
      metrics should contain(Metrics.GroupValueMessage(Metrics.MaxGroupOffsetLagMetric, config.cluster.name, groupId, value = 20))
    }

    "max group time lag metric" in {
      metrics should contain(Metrics.GroupValueMessage(Metrics.MaxGroupTimeLagMetric, config.cluster.name, groupId, value = 0.02))
    }
  }

  "ConsumerGroupCollector should calculate max group metrics and send" - {
    val reporter = TestInbox[MetricsSink.Message]()

    val lookupTable = LookupTable.Table(20)
    lookupTable.addPoint(LookupTable.Point(100, 100))

    val state = ConsumerGroupCollector.CollectorState(
      topicPartitionTables = Domain.TopicPartitionTable(config.lookupTableSize, Map(
        topicPartition0 -> lookupTable.copy(),
        topicPartition1 -> lookupTable.copy(),
        topicPartition2 -> lookupTable.copy()
      )),
    )

    val behavior = ConsumerGroupCollector.collector(config, client, reporter.ref, state)
    val testKit = BehaviorTestKit(behavior)

    val newLatestOffsets = Domain.PartitionOffsets() ++ List(
      topicPartition0 -> Point(offset = 200, time = 200),
      topicPartition1 -> Point(offset = 200, time = 200),
      topicPartition2 -> Point(offset = 200, time = 200)
    )
    val newLastGroupOffsets = Domain.GroupOffsets() ++ List(
      gtp02 -> Point(offset = 180, time = 200),
      gtp12 -> Point(offset = 100, time = 200),
      gtp22 -> Point(offset = 180, time = 200),
    )

    testKit.run(ConsumerGroupCollector.OffsetsSnapshot(timestamp = timestampNow, List(groupId), newLatestOffsets, newLastGroupOffsets))

    val metrics = reporter.receiveAll()

    "max group offset lag metric" in {
      metrics should contain(Metrics.GroupValueMessage(Metrics.MaxGroupOffsetLagMetric, config.cluster.name, groupId, value = 100))
    }

    "max group time lag metric" in {
      metrics should contain(Metrics.GroupValueMessage(Metrics.MaxGroupTimeLagMetric, config.cluster.name, groupId, value = 0.1))
    }
  }
}
