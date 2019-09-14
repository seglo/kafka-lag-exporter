/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import akka.actor.typed.ActorRef
import com.lightbend.kafkalagexporter.MetricsSink._

object MetricsSink {
  trait Message
  final case class Stop(sender: ActorRef[KafkaClusterManager.Message]) extends Message

  final case class GaugeDefinition(name: String, help: String, labels: List[String])
  type MetricDefinitions = List[GaugeDefinition]

  trait clusterMetric extends Metric{
    def clusterName: String
  }

  trait Metric {
    def labels: List[String]
    def definition: GaugeDefinition
  }

  trait MetricValue extends clusterMetric {
    def value: Double
  }

  trait RemoveMetric extends clusterMetric
}

trait MetricsSink {
  def report(m: MetricValue): Unit
  def remove(m: RemoveMetric): Unit
  def stop(): Unit = ()
}
