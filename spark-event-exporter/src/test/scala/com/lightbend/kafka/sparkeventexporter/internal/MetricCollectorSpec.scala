package com.lightbend.kafka.sparkeventexporter.internal

import akka.actor.testkit.typed.scaladsl.{BehaviorTestKit, TestInbox}
import com.lightbend.kafka.kafkametricstools
import com.lightbend.kafka.kafkametricstools.Domain._
import com.lightbend.kafka.kafkametricstools.KafkaClient.KafkaClientContract
import com.lightbend.kafka.kafkametricstools.{KafkaCluster, MetricsSink}
import com.lightbend.kafka.sparkeventexporter.internal.Domain.SourceMetrics
import org.mockito.MockitoSugar
import org.scalatest.{Matchers, _}

class MetricCollectorSpec extends FreeSpec with Matchers with kafkametricstools.TestData with MockitoSugar {
  val client: KafkaClientContract = mock[KafkaClientContract]
  val sparkAppId = "my-spark-id-uuid"

  "MetricCollectorSpec should send" - {
    val reporter = TestInbox[MetricsSink.Message]()

    val state = MetricCollector.CollectorState(
      name = "my-app-foo",
      cluster = KafkaCluster("kafka-cluster-name", ""),
      latestOffsets = PartitionOffsets() + (topicPartition0 -> Measurements.Single(offset = 100, timestamp = 100)),
      lastOffsets = PartitionOffsets() + (topicPartition0 -> Measurements.Single(offset = 90, timestamp = 100))
    )

    val providedName = state.name
    val clusterName = state.cluster.name

    val behavior = MetricCollector.collector(client, reporter.ref, state)
    val testKit = BehaviorTestKit(behavior)

    val newOffsets = MetricCollector.NewOffsets(
      sparkAppId = sparkAppId,
      sourceMetrics = List(SourceMetrics(1000, 500, Map(topicPartition0 -> Measurements.Single(180, timestamp = 200)))),
      latestOffsets = PartitionOffsets() + (topicPartition0 -> Measurements.Single(offset = 200, timestamp = 200)),
      lastOffsets = PartitionOffsets() + (topicPartition0 -> Measurements.Single(offset = 180, timestamp = 200))
    )

    testKit.run(newOffsets)

    val metrics = reporter.receiveAll()

    "report 5 metrics" in { metrics.length shouldBe 5 }

//    "latest offset metric" in {
//      metrics should contain(
//        Metrics.LatestOffsetMetric(clusterName, sparkAppId, providedName, topicPartition0, value = 200))
//    }

    "last group offset metric" in {
      metrics should contain(
        Metrics.LastOffsetMetric(clusterName, sparkAppId, providedName, topicPartition0, value = 180))
    }

    "offset lag metric" in {
      metrics should contain(
        Metrics.OffsetLagMetric(clusterName, sparkAppId, providedName, topicPartition0, value = 20))
    }

    "time lag metric" in {
      metrics should contain(
        Metrics.TimeLagMetric(clusterName, sparkAppId, providedName, topicPartition0, value = 0.022))
    }

    "input throughput metric" in {
      metrics should contain(
        Metrics.InputRecordsPerSecondMetric(clusterName, sparkAppId, providedName, topicPartition0.topic, 1000))
    }

    "output throughput metric" in {
      metrics should contain(
        Metrics.OutputRecordsPerSecondMetric(clusterName, sparkAppId, providedName, topicPartition0.topic, 500))
    }
  }
}
