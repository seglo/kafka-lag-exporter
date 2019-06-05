/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{Behavior, PostStop}
import com.lightbend.kafkalagexporter.MetricsSink._

object MetricsReporter {
  def init(
    metricsSink: MetricsSink): Behavior[Message] = Behaviors.setup { _ =>
    reporter(metricsSink)
  }

  def reporter(metricsSink: MetricsSink): Behavior[Message] = Behaviors.receive {
    case (_, m: MetricValue) =>
      metricsSink.report(m)
      Behaviors.same
    case (_, rm: RemoveMetric) =>
      metricsSink.remove(rm)
      Behaviors.same
    case (context, Stop(sender)) =>
      Behaviors.stopped { () =>
        metricsSink.stop()
        context.log.info("Gracefully stopped Prometheus metrics endpoint HTTP server")
        sender ! KafkaClusterManager.Done
      }
    case (context, m) =>
      context.log.error(s"Unhandled metric message: $m")
      Behaviors.same
  }
}
