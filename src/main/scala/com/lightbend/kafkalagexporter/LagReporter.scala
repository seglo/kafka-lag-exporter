package com.lightbend.kafkalagexporter

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import io.prometheus.client.Summary
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports

object LagReporter {
  sealed trait Message
  case class Metric(foo: String) extends Message

  def init(appConfig: AppConfig, exporterCreator: () => HTTPServer): Behavior[Message] = Behaviors.setup { _ =>
    DefaultExports.initialize()
    val metrics = new Metrics()
    reporter(appConfig, exporterCreator(), metrics)
  }

  def reporter(appConfig: AppConfig, exporter: HTTPServer, metrics: Metrics): Behavior[LagReporter.Message] = Behaviors.receive {
    case (context, metric: LagReporter.Metric) =>
      metrics.offsetLag.observe(2.394)
      metrics.timeLag.observe(3.492)

      Behaviors.same
  }

}

class Metrics {
  val latestOffset: Summary = Summary.build(s"kafka_lag_exporter_latest_offset", "Latest offset of a partition").register()
  val groupOffset: Summary = Summary.build(s"kafka_lag_exporter_group_offset", "Last consumed offset of a partition").register()
  val offsetLag: Summary = Summary.build(s"kafka_lag_exporter_group_lag", "Offset lag").register()
  val timeLag: Summary = Summary.build(s"kafka_lag_exporter_group_lag_seconds", "Time lag").register()
}