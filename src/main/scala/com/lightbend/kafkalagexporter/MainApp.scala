package com.lightbend.kafkalagexporter

import java.util.concurrent.Executors

import akka.NotUsed
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Terminated}
import com.typesafe.config.ConfigFactory
import io.prometheus.client.exporter.HTTPServer

import scala.concurrent.ExecutionContext

object MainApp extends App {
  // Cached thread pool for various Kafka calls for non-blocking I/O
  val kafkaClientEc = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  val appConfig = AppConfig(ConfigFactory.load().getConfig("kafka-lag-exporter"))

  val clientCreator = () => KafkaClient(appConfig.bootstrapBrokers, appConfig.clientGroupId)(kafkaClientEc)
  val exporterCreator = () => new HTTPServer(appConfig.port)

  val main: Behavior[NotUsed] =
    Behaviors.setup { context =>
      val reporter: ActorRef[LagReporter.Message] = context.spawn(LagReporter.init(appConfig, exporterCreator), "lag-reporter")
      val collector: ActorRef[ConsumerGroupCollector.Message] = context.spawn(ConsumerGroupCollector.init(appConfig, clientCreator, reporter), "consumer-group-collector")

      collector ! ConsumerGroupCollector.Collect

      Behaviors.receiveSignal {
        case (_, Terminated(_)) => Behaviors.stopped
      }
    }

  ActorSystem(main, "kafkalagexporterapp")
}
