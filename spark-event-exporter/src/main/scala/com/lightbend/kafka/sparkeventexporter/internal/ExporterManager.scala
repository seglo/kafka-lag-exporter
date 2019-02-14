package com.lightbend.kafka.sparkeventexporter.internal
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import com.lightbend.kafka.kafkametricstools.KafkaClient.KafkaClientContract
import com.lightbend.kafka.kafkametricstools.{KafkaCluster, MetricsReporter, MetricsSink}
import com.lightbend.kafka.sparkeventexporter.Config
import com.lightbend.kafka.sparkeventexporter.internal.MetricCollector.CollectorState
import org.apache.spark.sql.streaming.StreamingQueryListener

object ExporterManager {
  sealed trait Message
  sealed trait Stop extends Message
  final case object Stop extends Stop

  def init(
            config: Config,
            cluster: KafkaCluster,
            metricsSinkCreator: () => MetricsSink,
            clientCreator: () => KafkaClientContract): Behavior[Message] =
    Behaviors.setup { context =>
      context.log.info("Starting Spark Events Exporter with configuration: \n{}", config)

      val reporter: ActorRef[MetricsSink.Message] = context.spawn(MetricsReporter.init(metricsSinkCreator), "lag-reporter")
      val collectorState = CollectorState(config.providedName, cluster)
      val collector: ActorRef[MetricCollector.Message] = context.spawn(
        MetricCollector.init(collectorState, clientCreator, reporter), "offset-collector")

      val listener: StreamingQueryListener = MetricsStreamingQueryListener(collector)
      config.sparkSession.streams.addListener(listener)

      main(reporter, collector)
    }

  def main(
            reporter: ActorRef[MetricsSink.Message],
            collector: ActorRef[MetricCollector.Message]): Behavior[Message] = Behaviors.receive {
    case (context, _: Stop) =>
      context.log.info("Attempting graceful shutdown")
      collector ! MetricCollector.Stop
      reporter ! MetricsSink.Stop
      Behaviors.stopped
  }
}
