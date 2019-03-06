package com.lightbend.kafka.kafkametricstools
import com.codahale.metrics.MetricRegistry
import com.lightbend.kafka.kafkametricstools.MetricsSink.{GaugeDefinition, Message, Metric, MetricDefinitions}
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.JavaConverters._

class CodahaleMetricsSinkSpec extends FlatSpec with Matchers {
  case class TestMetric(clusterName: String, providedName: String, topic: String, partition: Int, value: Double)
    extends Message with Metric {
    override def labels: List[String] =
      List(
        clusterName,
        providedName,
        topic,
        partition.toString
      )
  }
  val metricDefinitions: MetricDefinitions = Map(
    classOf[TestMetric] -> GaugeDefinition(
      "test_metric",
      "Test Metric",
      "cluster_name", "provided_name", "topic", "partition"
    )
  )
  "CodahaleMetricsSink" should "delimit metric labels correctly" in {

    val newMetricRegistered = () => {}
    val registry = new MetricRegistry

    val sink = CodahaleMetricsSink(
      registry,
      metricDefinitions,
      newMetricRegistered
    )

    val m = TestMetric("pipelines-strimzi", "application.streamlet.inlet", "application.otherstreamlet.outlet", 0, 47)

    sink.report(m)

    val (gaugeName, gauge) = registry.getGauges().asScala.head

    gauge shouldNot be(null)
    gauge.getValue should be(47)
    gaugeName should be("test_metric,cluster_name=pipelines-strimzi,provided_name=application.streamlet.inlet,topic=application.otherstreamlet.outlet,partition=0")
  }
}
