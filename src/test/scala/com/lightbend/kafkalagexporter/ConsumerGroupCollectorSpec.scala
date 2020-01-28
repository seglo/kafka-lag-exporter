/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import java.time.{Clock, Instant, ZoneId}

import akka.actor.testkit.typed.scaladsl.{BehaviorTestKit, TestInbox}
import akka.actor.typed.ActorRef
import com.lightbend.kafkalagexporter.KafkaClient.KafkaClientContract
import com.lightbend.kafkalagexporter.LookupTable._
import com.lightbend.kafkalagexporter.Domain._
import com.lightbend.kafkalagexporter.Metrics._
import org.mockito.MockitoSugar
import org.mockito.ArgumentMatchers._
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

    val behavior = ConsumerGroupCollector.collector(config, client, List(reporter.ref), state)
    val testKit = BehaviorTestKit(behavior)

    val newEarliestOffsets = PartitionOffsets(topicPartition0 -> Point(offset = 0, time = timestampNow))
    val newLatestOffsets = PartitionOffsets(topicPartition0 -> Point(offset = 200, time = timestampNow))
    val newLastGroupOffsets = GroupOffsets(gtpSingleMember -> Some(Point(offset = 180, time = timestampNow)))

    testKit.run(ConsumerGroupCollector.OffsetsSnapshot(timestamp = timestampNow, List(groupId), newEarliestOffsets, newLatestOffsets, newLastGroupOffsets))

    val metrics = reporter.receiveAll()

    "report 9 metrics" in { metrics.length shouldBe 9 }

    "earliest offset metric" in {
      metrics should contain(
        Metrics.TopicPartitionValueMessage(EarliestOffsetMetric, config.cluster.name, topicPartition0, value = 0))
    }

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

    "sum group offset lag metric" in {
      metrics should contain(GroupValueMessage(SumGroupOffsetLagMetric, config.cluster.name, groupId, value = 20))
    }

    "sum topic offset lag metric" in {
      metrics should contain(GroupTopicValueMessage(SumGroupTopicOffsetLagMetric, config.cluster.name, groupId, topic, value = 20))
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

    val behavior = ConsumerGroupCollector.collector(config, client, List(reporter.ref), state)
    val testKit = BehaviorTestKit(behavior)

    val newEarliestOffsets = PartitionOffsets(
      topicPartition0 -> Point(offset = 0, time = 200),
      topicPartition1 -> Point(offset = 0, time = 200),
      topicPartition2 -> Point(offset = 0, time = 200)
    )
    val newLatestOffsets = PartitionOffsets(
      topicPartition0 -> Point(offset = 200, time = 200),
      topicPartition1 -> Point(offset = 200, time = 200),
      topicPartition2 -> Point(offset = 200, time = 200)
    )
    val newLastGroupOffsets = GroupOffsets(
      gtp0 -> Some(Point(offset = 180, time = 200)),
      gtp1 -> Some(Point(offset = 100, time = 200)),
      gtp2 -> Some(Point(offset = 180, time = 200)),
    )

    testKit.run(ConsumerGroupCollector.OffsetsSnapshot(timestamp = timestampNow, List(groupId), newEarliestOffsets, newLatestOffsets, newLastGroupOffsets))

    val metrics = reporter.receiveAll()

    "max group offset lag metric" in {
      metrics should contain(GroupValueMessage(MaxGroupOffsetLagMetric, clusterName, groupId, value = 100))
    }

    "max group time lag metric" in {
      metrics should contain(GroupValueMessage(MaxGroupTimeLagMetric, clusterName, groupId, value = 0.1))
    }
  }

  "ConsumerGroupCollector should sum the group offset lag metrics and send" - {
    val reporter = TestInbox[MetricsSink.Message]()

    val lookupTable = Table(20)
    lookupTable.addPoint(Point(100, 100))

    val state = ConsumerGroupCollector.CollectorState(
      topicPartitionTables = TopicPartitionTable(config.lookupTableSize, Map(
        topicPartition0 -> lookupTable.copy(),
        topicPartition1 -> lookupTable.copy(),
        topicPartition2 -> lookupTable.copy(),
        topic2Partition0 -> lookupTable.copy()
      )),
    )

    val behavior = ConsumerGroupCollector.collector(config, client, List(reporter.ref), state)
    val testKit = BehaviorTestKit(behavior)

    val newEarliestOffsets = PartitionOffsets(
      topicPartition0 -> Point(offset = 0, time = 100),
      topicPartition1 -> Point(offset = 0, time = 100),
      topicPartition2 -> Point(offset = 0, time = 100),
      topic2Partition0 -> Point(offset = 0, time = 100)
    )
    val newLatestOffsets = PartitionOffsets(
      topicPartition0 -> Point(offset = 100, time = 200),
      topicPartition1 -> Point(offset = 100, time = 200),
      topicPartition2 -> Point(offset = 100, time = 200),
      topic2Partition0 -> Point(offset = 100, time = 200)
    )
    val newLastGroupOffsets = GroupOffsets(
      gtp0 -> Some(Point(offset = 10, time = 200)),
      gtp1 -> Some(Point(offset = 20, time = 200)),
      gtp2 -> Some(Point(offset = 30, time = 200)),
      gt2p0 -> Some(Point(offset = 40, time = 200))
    )

    testKit.run(ConsumerGroupCollector.OffsetsSnapshot(timestamp = timestampNow, List(groupId), newEarliestOffsets, newLatestOffsets, newLastGroupOffsets))

    val metrics = reporter.receiveAll()

    "sum of offset lag metric" in {
      metrics should contain(GroupValueMessage(SumGroupOffsetLagMetric, clusterName, groupId, value = 300))
    }

    "sum of offset lag by topic metric" in {
      metrics should contain(GroupTopicValueMessage(SumGroupTopicOffsetLagMetric, clusterName, groupId, topic, value = 240))
      metrics should contain(GroupTopicValueMessage(SumGroupTopicOffsetLagMetric, clusterName, groupId, topic2, value = 60))
    }
  }

  "ConsumerGroupCollector should report group offset, lag, and time lag as NaN when no group offsets found" - {
    val reporter = TestInbox[MetricsSink.Message]()

    val lookupTable = Table(20)
    lookupTable.addPoint(Point(100, 100))

    val state = ConsumerGroupCollector.CollectorState(
      topicPartitionTables = TopicPartitionTable(config.lookupTableSize, Map(topicPartition0 -> lookupTable))
    )

    val behavior = ConsumerGroupCollector.collector(config, client, List(reporter.ref), state)
    val testKit = BehaviorTestKit(behavior)

    val newEarliestOffsets = PartitionOffsets(topicPartition0 -> Point(offset = 0, time = timestampNow))
    val newLatestOffsets = PartitionOffsets(topicPartition0 -> Point(offset = 200, time = timestampNow))
    val newLastGroupOffsets = GroupOffsets(gtpSingleMember -> None)

    testKit.run(ConsumerGroupCollector.OffsetsSnapshot(timestamp = timestampNow, List(groupId), newEarliestOffsets, newLatestOffsets, newLastGroupOffsets))

    val metrics = reporter.receiveAll()

    // using `collectFirst` to pattern match metric messages because `Double.NaN != Double.NaN`. instead we match all
    // parameters then use `isNaN` to determine if value is NaN
    "last group offset metric" in {
      metrics.collectFirst {
        case GroupPartitionValueMessage(`LastGroupOffsetMetric`, config.cluster.name, `gtpSingleMember`, value) if value.isNaN => true
      }.nonEmpty shouldBe true
    }

    "offset lag metric" in {
      metrics.collectFirst {
        case GroupPartitionValueMessage(`OffsetLagMetric`, config.cluster.name, `gtpSingleMember`, value) if value.isNaN => true
      }.nonEmpty shouldBe true
    }

    "time lag metric" in {
      metrics.collectFirst {
        case GroupPartitionValueMessage(`TimeLagMetric`, config.cluster.name, `gtpSingleMember`, value) if value.isNaN => true
      }.nonEmpty shouldBe true
    }

    "max group offset lag metric" in {
      metrics.collectFirst {
        case GroupValueMessage(`MaxGroupOffsetLagMetric`, config.cluster.name, `groupId`, value) if value.isNaN => true
      }.nonEmpty shouldBe true
    }

    "max group time lag metric" in {
      metrics.collectFirst {
        case GroupValueMessage(`MaxGroupTimeLagMetric`, config.cluster.name, `groupId`, value) if value.isNaN => true
      }.nonEmpty shouldBe true
    }

    "earliest offset metric" in {
      metrics should contain(
        Metrics.TopicPartitionValueMessage(EarliestOffsetMetric, config.cluster.name, topicPartition0, value = 0))
    }

    "latest offset metric" in {
      metrics should contain(
        Metrics.TopicPartitionValueMessage(LatestOffsetMetric, config.cluster.name, topicPartition0, value = 200))
    }

    "topic offset lag metric" in {
      metrics.collectFirst {
        case GroupTopicValueMessage(`SumGroupTopicOffsetLagMetric`, config.cluster.name, `groupId`, `topic`, value) if value.isNaN => true
      }.nonEmpty shouldBe true
    }
  }

  "ConsumerGroupCollector should evict data when group metadata changes" - {
    val reporter = TestInbox[MetricsSink.Message]()

    def newState() = {
      val lastTimestamp = timestampNow - 100
      val tpTables = TopicPartitionTable(config.lookupTableSize, Map(topicPartition0 -> lookupTableOnePoint.copy()))
      ConsumerGroupCollector.CollectorState(
        topicPartitionTables = tpTables,
        lastSnapshot = Some(ConsumerGroupCollector.OffsetsSnapshot(
          timestamp = lastTimestamp,
          groups = List(groupId),
          earliestOffsets = PartitionOffsets(topicPartition0 -> Point(offset = 0, time = lastTimestamp)),
          latestOffsets = PartitionOffsets(topicPartition0 -> Point(offset = 200, time = lastTimestamp)),
          lastGroupOffsets = GroupOffsets(gtpSingleMember -> Some(Point(offset = 180, time = lastTimestamp)))
        ))
      )
    }

    "remove metric for consumer ids no longer being reported" in {
      val behavior = ConsumerGroupCollector.collector(config, client, List(reporter.ref), newState())
      val testKit = BehaviorTestKit(behavior)

      val newConsumerId = s"$consumerId-new"
      val snapshot = ConsumerGroupCollector.OffsetsSnapshot(
        timestamp = timestampNow,
        groups = List(groupId),
        earliestOffsets = PartitionOffsets(topicPartition0 -> Point(offset = 0, time = 200)),
        latestOffsets = PartitionOffsets(topicPartition0 -> Point(offset = 200, time = 200)),
        lastGroupOffsets = GroupOffsets(gtpSingleMember.copy(consumerId = newConsumerId) -> Some(Point(offset = 180, time = 200)))
      )

      testKit.run(snapshot)

      val metrics = reporter.receiveAll()

      metrics should contain(GroupPartitionRemoveMetricMessage(LastGroupOffsetMetric, clusterName, gtpSingleMember))
      metrics should contain(GroupPartitionRemoveMetricMessage(OffsetLagMetric, clusterName, gtpSingleMember))
      metrics should contain(GroupPartitionRemoveMetricMessage(TimeLagMetric, clusterName, gtpSingleMember))
    }

    "remove metrics for topic group partitions no longer being reported" in {
      val behavior = ConsumerGroupCollector.collector(config, client, List(reporter.ref), newState())
      val testKit = BehaviorTestKit(behavior)

      val newGroupId = s"$groupId-new"
      val snapshot = ConsumerGroupCollector.OffsetsSnapshot(
        timestamp = timestampNow,
        groups = List(newGroupId),
        earliestOffsets = PartitionOffsets(topicPartition0 -> Point(offset = 0, time = 200)),
        latestOffsets = PartitionOffsets(topicPartition0 -> Point(offset = 200, time = 200)),
        lastGroupOffsets = GroupOffsets(gtpSingleMember.copy(id = newGroupId) -> Some(Point(offset = 180, time = 200)))
      )

      testKit.run(snapshot)

      val metrics = reporter.receiveAll()

      metrics should contain(GroupPartitionRemoveMetricMessage(LastGroupOffsetMetric, clusterName, gtpSingleMember))
      metrics should contain(GroupPartitionRemoveMetricMessage(OffsetLagMetric, clusterName, gtpSingleMember))
      metrics should contain(GroupPartitionRemoveMetricMessage(TimeLagMetric, clusterName, gtpSingleMember))
      metrics should contain(GroupRemoveMetricMessage(MaxGroupTimeLagMetric, clusterName, groupId))
      metrics should contain(GroupRemoveMetricMessage(MaxGroupOffsetLagMetric, clusterName, groupId))
      metrics should contain(GroupTopicRemoveMetricMessage(SumGroupTopicOffsetLagMetric, clusterName, groupId, topic))
    }

    "remove metrics for topic partitions no longer being reported" - {
      // spy required to inspect internal state of behavior.
      // see example: https://gist.github.com/patriknw/5f0c54f5748d25d7928389165098b89e#file-synctestingfsmmockitoexamplespec
      val collectorBehavior = spy(new ConsumerGroupCollector.CollectorBehavior)

      val behavior = collectorBehavior.collector(config, client, List(reporter.ref), newState())
      val testKit = BehaviorTestKit(behavior)

      val snapshot = ConsumerGroupCollector.OffsetsSnapshot(
        timestamp = timestampNow,
        groups = List(),
        earliestOffsets = PartitionOffsets(),
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
        metrics should contain(GroupRemoveMetricMessage(SumGroupOffsetLagMetric, clusterName, groupId))
        metrics should contain(GroupTopicRemoveMetricMessage(SumGroupTopicOffsetLagMetric, clusterName, groupId, topic))
      }

      "topic partition in topic partition table removed" in {
        // collector is called once during test setup and once for state transition after processing OffsetSnapshot msg
        verify(collectorBehavior, times(2)).collector(
          any[ConsumerGroupCollector.CollectorConfig],
          any[KafkaClient.KafkaClientContract],
          any[List[ActorRef[MetricsSink.Message]]],
          argThat[ConsumerGroupCollector.CollectorState](_.topicPartitionTables.tables.isEmpty)
        )
      }

    }
  }
}
