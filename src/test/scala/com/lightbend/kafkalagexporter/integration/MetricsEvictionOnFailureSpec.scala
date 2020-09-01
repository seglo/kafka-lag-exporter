/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter.integration

import com.lightbend.kafkalagexporter.Metrics._

class MetricsEvictionOnFailureSpec extends SpecBase(exporterPort = ExporterPorts.MetricsEvictionOnFailureSpec) {
  "kafka lag exporter" should {
    "not report metrics for group members or partitions after a failure" in {
      val group = createGroupId(1)
      val partition = "0"
      val topic = createTopic(1, 1, 1)

      val offsetsToCommit = 5
      val totalOffsets = 10

      val rules = List(
        Rule.create(LatestOffsetMetric, (actual: String) => actual shouldBe (totalOffsets + 1).toDouble.toString, clusterName, topic, partition),
        Rule.create(EarliestOffsetMetric, (actual: String) => actual shouldBe 0.toDouble.toString, clusterName, topic, partition),
        Rule.create(LastGroupOffsetMetric, (actual: String) => actual shouldBe offsetsToCommit.toDouble.toString, clusterName, group, topic, partition),
        Rule.create(OffsetLagMetric, (actual: String) => actual shouldBe (offsetsToCommit + 1).toDouble.toString, clusterName, group, topic, partition),
        Rule.create(TimeLagMetric, (_: String) => (), clusterName, group, topic, partition),
        Rule.create(MaxGroupOffsetLagMetric, (actual: String) => actual shouldBe (offsetsToCommit + 1).toDouble.toString, clusterName, group),
        Rule.create(MaxGroupTimeLagMetric, (_: String) => (), clusterName, group)
      )

      val simulator = new LagSimulator(topic, group)
      simulator.produceElements(totalOffsets)
      simulator.consumeElements(offsetsToCommit)
      simulator.shutdown()

      eventually(scrapeAndAssert(exporterPort, "Assert offset-based metrics", rules: _*))

      stopKafka()

      eventually(scrapeAndAssertDne(exporterPort, "Assert offset-based metrics no longer exist", rules: _*))
    }
  }
}
