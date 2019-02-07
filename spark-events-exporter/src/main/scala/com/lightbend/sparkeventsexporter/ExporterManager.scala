package com.lightbend.sparkeventsexporter
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import com.lightbend.kafkaclientmetrics.KafkaClient.KafkaClientContract
import com.lightbend.kafkaclientmetrics.{KafkaCluster, MetricsReporter, PrometheusEndpoint}
import com.lightbend.kafkaclientmetrics.PrometheusEndpoint.PrometheusMetricsEndpointContract
import com.lightbend.sparkeventsexporter.OffsetCollector.CollectorState
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.streaming.StreamingQueryListener

object ExporterManager {
  sealed trait Message
  sealed trait Stop extends Message
  final case object Stop extends Stop

  def init(
            appConfig: AppConfig,
            cluster: KafkaCluster,
            endpointCreator: () => PrometheusMetricsEndpointContract,
            clientCreator: () => KafkaClientContract,
            session: SparkSession): Behavior[Message] =
    Behaviors.setup { context =>
      context.log.info("Starting Spark Events Exporter with configuration: \n{}", appConfig)

      val reporter: ActorRef[PrometheusEndpoint.Message] = context.spawn(MetricsReporter.init(endpointCreator), "lag-reporter")
      val collectorState = CollectorState(appConfig.providedName, cluster)
      val collector: ActorRef[OffsetCollector.Message] = context.spawn(OffsetCollector.init(collectorState, clientCreator, reporter), "offset-collector")

      val listener: StreamingQueryListener = MetricsStreamingQueryListener(collector)
      session.streams.addListener(listener)

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
