package com.lightbend.sparkeventsexporter

import akka.actor.testkit.typed.scaladsl.{BehaviorTestKit, TestInbox}
import com.lightbend.kafkaclientmetrics
import com.lightbend.kafkaclientmetrics.Domain._
import com.lightbend.kafkaclientmetrics.KafkaClient.KafkaClientContract
import com.lightbend.kafkaclientmetrics.{KafkaCluster, PrometheusEndpoint}
import com.lightbend.sparkeventsexporter.Domain.SourceMetrics
import org.mockito.MockitoSugar
import org.scalatest.{Matchers, _}

class OffsetCollectorSpec extends FreeSpec with Matchers with kafkaclientmetrics.TestData with MockitoSugar {
  val client: KafkaClientContract = mock[KafkaClientContract]
  val sparkAppId = "my-spark-id-uuid"

  "OffsetCollectorSpec should send" - {
    val reporter = TestInbox[PrometheusEndpoint.Message]()

    val state = OffsetCollector.CollectorState(
      name = "my-app-foo",
      cluster = KafkaCluster("kafka-cluster-name", ""),
      latestOffsets = PartitionOffsets() + (topicPartition0 -> Measurements.Single(offset = 100, timestamp = 100)),
      lastOffsets = PartitionOffsets() + (topicPartition0 -> Measurements.Single(offset = 90, timestamp = 100))
    )

    val providedName = state.name
    val clusterName = state.cluster.name

    val behavior = OffsetCollector.collector(client, reporter.ref, state)
    val testKit = BehaviorTestKit(behavior)

    val newOffsets = OffsetCollector.NewOffsets(
      sparkAppId = sparkAppId,
      sourceMetrics = List(SourceMetrics(1000, 500, Map(topicPartition0 -> Measurements.Single(180, timestamp = 200)))),
      latestOffsets = PartitionOffsets() + (topicPartition0 -> Measurements.Single(offset = 200, timestamp = 200)),
      lastOffsets = PartitionOffsets() + (topicPartition0 -> Measurements.Single(offset = 180, timestamp = 200))
    )

    testKit.run(newOffsets)

    val metrics = reporter.receiveAll()

    "report 6 metrics" in { metrics.length shouldBe 6 }

    "latest offset metric" in {
      metrics should contain(
        Metrics.LatestOffsetMetric(clusterName, sparkAppId, providedName, topicPartition0, value = 200))
    }

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
