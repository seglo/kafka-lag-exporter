package com.lightbend.kafka.kafkalagexporter.integration

import akka.kafka.Subscriptions
import akka.kafka.scaladsl.Consumer
import akka.stream.scaladsl.Keep
import akka.stream.testkit.scaladsl.StreamTestKit.assertAllStagesStopped
import akka.stream.testkit.scaladsl.TestSink
import com.lightbend.kafka.kafkalagexporter.Metrics._
import net.manub.embeddedkafka.EmbeddedKafkaConfig
import org.scalatest.BeforeAndAfterEach

import scala.concurrent.Await
import scala.concurrent.duration._

class IntegrationSpec extends SpecBase(kafkaPort = 9094) with BeforeAndAfterEach {

  implicit val patience: PatienceConfig = PatienceConfig(30 seconds, 1 second)

  // TODO: Add lag in seconds test
  "kafka lag exporter" should {
    "reports metrics" in {
      assertAllStagesStopped {
        val topic = createTopic(1, 1, 1)
        val group = createGroupId(1)

        val partition = "0"
        val offsetsToCommit = 5
        val totalOffsets = 10

        val rules = List(
          Rule.create(classOf[LatestOffsetMetric], expectation = totalOffsets + 1, clusterName, topic, partition),
          Rule.create(classOf[MaxGroupOffsetLagMetric], expectation = offsetsToCommit, clusterName, group),
          Rule.create(classOf[LastGroupOffsetMetric], expectation = offsetsToCommit + 1, clusterName, group, topic, partition),
          Rule.create(classOf[OffsetLagMetric], expectation = offsetsToCommit, clusterName, group, topic, partition)
        )

        givenInitializedTopic(topic)

        Await.result(produce(topic, 1 to totalOffsets), remainingOrDefault)

        val (control, probe) = Consumer
          .committableSource(consumerDefaults.withGroupId(group), Subscriptions.topics(topic))
          .filterNot(_.record.value == InitialMsg)
          .map { elem =>
            elem.committableOffset.commitScaladsl()
            log.debug("Committed offset: {}", elem.committableOffset.partitionOffset)
            elem
          }
          .toMat(TestSink.probe)(Keep.both)
          .run()

        probe
          .request(offsetsToCommit)
          .expectNextN(offsetsToCommit)

        val stopped = control.stop()
        Await.result(stopped, remainingOrDefault)

        eventually {
          val results = scrape(8000, rules).futureValue
          log.debug("Asserting metrics..")
          results.foreach(_.assert())
          log.debug("Asserting metrics successful")
        }

        control.shutdown()
        probe.cancel()
      }
    }
  }
}
