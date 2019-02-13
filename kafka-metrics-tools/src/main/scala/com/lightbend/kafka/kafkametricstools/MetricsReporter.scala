package com.lightbend.kafka.kafkametricstools

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{Behavior, PostStop}
import com.lightbend.kafka.kafkametricstools.PrometheusEndpoint.{Message, Metric, PrometheusMetricsEndpointContract, Stop}

object MetricsReporter {
  def init(endpointCreator: () => PrometheusMetricsEndpointContract): Behavior[Message] = Behaviors.setup { _ =>
    reporter(endpointCreator())
  }

  def reporter(endpoint: PrometheusMetricsEndpointContract): Behavior[Message] = Behaviors.receive {
    case (_, m: Metric) =>
      endpoint.report(m)
      Behaviors.same
    case (context, _: Stop) =>
      Behaviors.stopped {
        Behaviors.receiveSignal {
          case (_, PostStop) =>
            endpoint.stop()
            context.log.info("Gracefully stopped Prometheus metrics endpoint HTTP server")
            Behaviors.same
        }
      }
    case (context, m) =>
      context.log.error(s"Unhandled metric message: $m")
      Behaviors.same
  }
}
