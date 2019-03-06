package com.lightbend.kafka.kafkametricstools

import com.codahale.metrics.{Gauge, MetricRegistry}
import com.lightbend.kafka.kafkametricstools.CodahaleMetricsSink.SparkGauge
import com.lightbend.kafka.kafkametricstools.MetricsSink.{Metric, MetricDefinitions}

object CodahaleMetricsSink {
  def apply(registry: MetricRegistry, definitions: MetricDefinitions, newMetricRegistered: () => Unit): MetricsSink =
    new CodahaleMetricsSink(registry, definitions, newMetricRegistered)

  class SparkGauge extends Gauge[Double] {
    private var _value: Double = 0
    def setValue(value: Double): Unit = _value = value
    override def getValue: Double = _value
  }
}

class CodahaleMetricsSink private(registry: MetricRegistry, definitions: MetricDefinitions, newMetricRegistered: () => Unit)
  extends MetricsSink {

  private def upsertGauge(metricType: Class[_], labels: List[String]): SparkGauge = {
    def newGauge(name: String): SparkGauge = {
      val gauge = new SparkGauge
      registry.register(name, gauge)
      newMetricRegistered()
      gauge
    }

    val defn = definitions.getOrElse(metricType, throw new IllegalArgumentException(s"No metric with type $metricType defined"))
    val metricName = encodeNameWithLabels(labels, defn)

    if (registry.getGauges.containsKey(metricName))
      registry.getGauges().get(metricName).asInstanceOf[SparkGauge]
    else
      newGauge(metricName)
  }

  /**
    * Encodes label names and values into the metric name to make parsing easier downstream.
    * i.e. label_one=some-value,label_two=some-other.value,label_three=yet-another-value
    */
  private def encodeNameWithLabels(
    labels: List[String],
    defn: MetricsSink.GaugeDefinition
  ) = {
    defn.name + "," + defn.label
      .zip(labels)
      .map { case (name, value) => s"$name=$value" }
      .mkString(",")
  }
  override def report(m: Metric): Unit = {
    upsertGauge(m.getClass, m.labels).setValue(m.value)
  }
}
