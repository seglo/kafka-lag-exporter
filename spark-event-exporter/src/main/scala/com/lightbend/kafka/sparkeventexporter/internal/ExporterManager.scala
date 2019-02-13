package com.lightbend.kafka.sparkeventexporter.internal
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import com.lightbend.kafka.kafkametricstools.KafkaClient.KafkaClientContract
import com.lightbend.kafka.kafkametricstools.PrometheusEndpoint.PrometheusMetricsEndpointContract
import com.lightbend.kafka.kafkametricstools.{KafkaCluster, MetricsReporter, PrometheusEndpoint}
import com.lightbend.kafka.sparkeventexporter.Config
import com.lightbend.kafka.sparkeventexporter.internal.OffsetCollector.CollectorState
import org.apache.spark.sql.streaming.StreamingQueryListener

object ExporterManager {
  sealed trait Message
  sealed trait Stop extends Message
  final case object Stop extends Stop

  def init(
            config: Config,
            cluster: KafkaCluster,
            endpointCreator: () => PrometheusMetricsEndpointContract,
            clientCreator: () => KafkaClientContract): Behavior[Message] =
    Behaviors.setup { context =>
      context.log.info("Starting Spark Events Exporter with configuration: \n{}", config)

      val reporter: ActorRef[PrometheusEndpoint.Message] = context.spawn(MetricsReporter.init(endpointCreator), "lag-reporter")
      val collectorState = CollectorState(config.providedName, cluster)
      val collector: ActorRef[OffsetCollector.Message] = context.spawn(OffsetCollector.init(collectorState, clientCreator, reporter), "offset-collector")

      val listener: StreamingQueryListener = MetricsStreamingQueryListener(collector)
      config.sparkSession.streams.addListener(listener)

      main(reporter, collector)
    }

  def main(
            reporter: ActorRef[PrometheusEndpoint.Message],
            collector: ActorRef[OffsetCollector.Message]): Behavior[Message] = Behaviors.receive {
    case (context, _: Stop) =>
      context.log.info("Attempting graceful shutdown")
      collector ! OffsetCollector.Stop
      reporter ! PrometheusEndpoint.Stop
      Behaviors.stopped
  }
}
