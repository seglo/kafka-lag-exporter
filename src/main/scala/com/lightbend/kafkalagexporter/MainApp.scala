package com.lightbend.kafkalagexporter

import java.util.concurrent.Executors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object MainApp extends App {
  sealed trait Stop
  case object Stop extends Stop
  // Cached thread pool for various Kafka calls for non-blocking I/O
  val kafkaClientEc = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  val appConfig = AppConfig(ConfigFactory.load().getConfig("kafka-lag-exporter"))

  val clientCreator = () => KafkaClient(appConfig.bootstrapBrokers, appConfig.clientGroupId)(kafkaClientEc)
  val endpointCreator = () => PrometheusMetricsEndpoint(appConfig.port)

  val main: Behavior[Stop] =
    Behaviors.setup { context =>
      context.log.info("Starting Kafka Lag Exporter with configuration: \n{}", appConfig)

      val reporter: ActorRef[LagReporter.Message] = context.spawn(LagReporter.init(appConfig, endpointCreator), "lag-reporter")
      val collector: ActorRef[ConsumerGroupCollector.Message] = context.spawn(ConsumerGroupCollector.init(appConfig, clientCreator, reporter), "consumer-group-collector")

      collector ! ConsumerGroupCollector.Collect

      Behaviors.receiveMessage {
        _: Stop =>
          context.log.info("Attempting graceful shutdown")
          collector ! ConsumerGroupCollector.Stop
          reporter ! LagReporter.Stop
          Behaviors.stopped
      }
    }

  val system = ActorSystem(main, "kafkalagexporterapp")

  // Add shutdown hook to respond to SIGTERM and gracefully shutdown stream
  sys.ShutdownHookThread {
    system ! Stop
    Await.result(system.whenTerminated, 5 seconds)
  }
}
