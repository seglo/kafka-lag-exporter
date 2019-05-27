/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import com.lightbend.kafkalagexporter.MetricsSink._

object MetricsSink {
  trait Message
  sealed trait Stop extends Message
  final case object Stop extends Stop

  final case class GaugeDefinition(name: String, help: String, labels: List[String])
  type MetricDefinitions = List[GaugeDefinition]

  trait Metric {
    def labels: List[String]
    def definition: GaugeDefinition
  }

  trait MetricValue extends Metric {
    def value: Double
  }

  trait RemoveMetric extends Metric
}

trait MetricsSink {
  def report(m: MetricValue): Unit
  def remove(m: RemoveMetric): Unit
  def stop(): Unit = ()
}
