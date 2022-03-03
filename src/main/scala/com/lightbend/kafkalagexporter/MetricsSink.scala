/*
 * Copyright (C) 2019-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import akka.actor.typed.ActorRef
import com.lightbend.kafkalagexporter.MetricsSink._

object MetricsSink {
  trait Message
  final case class Stop(sender: ActorRef[KafkaClusterManager.Message]) extends MetricsSink.Message

  final case class GaugeDefinition(name: String, help: String, labels: List[String])
  type MetricDefinitions = List[GaugeDefinition]

  trait ClusterMetric extends Metric{
    def clusterName: String
  }

  trait Metric {
    def labels: List[String]
    def definition: GaugeDefinition
  }

  trait MetricValue extends ClusterMetric {
    def value: Double
  }

  trait RemoveMetric extends ClusterMetric
}

trait MetricsSink {
  def report(m: MetricValue): Unit
  def remove(m: RemoveMetric): Unit
  def stop(): Unit = ()
}
