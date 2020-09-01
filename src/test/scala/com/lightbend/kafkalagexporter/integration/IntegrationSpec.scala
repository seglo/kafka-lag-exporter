/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter.integration

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.stream.testkit.scaladsl.StreamTestKit.assertAllStagesStopped
import com.lightbend.kafkalagexporter.Metrics._

import scala.util.Try

class IntegrationSpec extends SpecBase(exporterPort = ExporterPorts.IntegrationSpec) {

  "kafka lag exporter" should {
    val group = createGroupId(1)
    val partition = "0"

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

        eventually(scrapeAndAssert(exporterPort, "Assert offset-based metrics", rules: _*))

        simulator.shutdown()
      }
    }

    "reports time lag increasing over time" in {
      val topic = createTopic(1, 1, 1)

      val testKit = ActorTestKit()

      val simulator = new LagSimulator(topic, group)
      val simulatorActor = testKit.spawn(lagSimActor(simulator), "app-simulator")

      simulatorActor ! Tick(10, 5)

      var lastLagInTime: Double = 0

      val isIncreasing: String => Unit = (actual: String) => {
        val parsedDoubleTry = Try(actual.toDouble)
        assert(parsedDoubleTry.isSuccess)
        val parsedDouble = parsedDoubleTry.get
        parsedDouble should be > lastLagInTime
        lastLagInTime = parsedDouble
      }

      val isIncreasingRule = Rule.create(TimeLagMetric, isIncreasing, clusterName, group, topic, partition)

      (1 to 3).foreach { i =>
        eventually(scrapeAndAssert(exporterPort, s"Assert lag in time metrics are increasing ($i)", isIncreasingRule))
      }

      testKit.stop(simulatorActor)
    }

    "report poll time metric greater than 0 ms" in {
      assertAllStagesStopped {
        val rule = Rule.create(PollTimeMetric, (actual: String) => actual.toDouble should be > 0d, clusterName)
        eventually(scrapeAndAssert(exporterPort, "Assert poll time metric", rule))
      }
    }
  }
}
