package com.lightbend.kafka.kafkametricstools

import com.lightbend.kafka.kafkametricstools.MetricsSink.Metric

object MetricsSink {
  trait Message
  sealed trait Stop extends Message
  final case object Stop extends Stop

  type MetricDefinitions = Map[Class[_], GaugeDefinition]

  trait Metric {
    def value: Double
    def labels: List[String]
  }

  final case class GaugeDefinition(name: String, help: String, label: String*)
}

trait MetricsSink {
  def report(m: Metric): Unit
  def stop(): Unit = ()
}
