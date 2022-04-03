/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2022 Sean Glover <https://seanglover.com>
 */

package com.lightbend.kafkalagexporter.integration

import akka.stream.testkit.scaladsl.StreamTestKit.assertAllStagesStopped
import com.lightbend.kafkalagexporter.Metrics.{EarliestOffsetMetric, LastGroupOffsetMetric, LatestOffsetMetric, MaxGroupOffsetLagMetric, MaxGroupTimeLagMetric, OffsetLagMetric, TimeLagMetric}
import org.scalactic.source.Position
import org.scalatest.concurrent.PatienceConfiguration
import org.scalatest.time._

import scala.concurrent.duration.DurationInt

class MinikubeIntegrationSpec extends MinikubeSpecBase {

  override def beforeAll(): Unit = {
    setUp()
    install()
    waitForExporterService()
  }

  override def afterAll(): Unit = {
    cleanUp()
    uninstall()
  }

  "kafka lag exporter on minikube" should {
    "install" in {
      succeed
    }

    "report lag" in {
      val topic = createTopic()
      println(s"created $topic")
      succeed
    }

    val group = createGroupId(1)
    val partition = "0"
    val clusterName = "default"

    "reports offset-based lag metrics" in {
      assertAllStagesStopped {
        val topic = createTopic(1, 1, 1)

        val offsetsToCommit = 5
        val totalOffsets = 10

        val rules = List(
          Rule.create(LatestOffsetMetric, (actual: String) => actual shouldBe (totalOffsets + 1).toDouble.toString, clusterName, topic, partition),
          Rule.create(EarliestOffsetMetric, (actual: String) => actual shouldBe 0.toDouble.toString, clusterName, topic, partition),
          Rule.create(LastGroupOffsetMetric, (actual: String) => actual shouldBe offsetsToCommit.toDouble.toString, clusterName, group, topic, partition),
          Rule.create(OffsetLagMetric, (actual: String) => actual shouldBe (offsetsToCommit + 1).toDouble.toString, clusterName, group, topic, partition),
          // TODO: update test so we can assert actual lag in time.  keep producer running for more than two polling cycles.
          Rule.create(TimeLagMetric, (_: String) => (), clusterName, group, topic, partition),
          Rule.create(MaxGroupOffsetLagMetric, (actual: String) => actual shouldBe (offsetsToCommit + 1).toDouble.toString, clusterName, group),
          Rule.create(MaxGroupTimeLagMetric, (_: String) => (), clusterName, group)
        )

        val simulator = new LagSimulator(topic, group)
        simulator.produceElements(totalOffsets)
        simulator.consumeElements(offsetsToCommit)

        eventually(scrapeAndAssert(exporterHostPort, "Assert offset-based metrics", rules: _*))

        simulator.shutdown()
      }
    }
  }
}
