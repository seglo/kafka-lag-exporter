package com.lightbend.kafka.kafkalagexporter

import java.util.concurrent.Executors

import akka.actor.typed.ActorSystem
import com.lightbend.kafka.kafkametricstools.{KafkaClient, PrometheusEndpointSink}
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object MainApp extends App {
  val system = start()

  // Add shutdown hook to respond to SIGTERM and gracefully shutdown the actor system
  sys.ShutdownHookThread {
    system ! KafkaClusterManager.Stop
    Await.result(system.whenTerminated, 5 seconds)
  }

  def start(config: Config = ConfigFactory.load()): ActorSystem[KafkaClusterManager.Message] = {
    // Cached thread pool for various Kafka calls for non-blocking I/O
    val kafkaClientEc = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

    val appConfig = AppConfig(config)

    val clientCreator = (bootstrapBrokers: String) =>
      KafkaClient(bootstrapBrokers, appConfig.clientGroupId, appConfig.clientTimeout)(kafkaClientEc)
    val endpointCreator = () => PrometheusEndpointSink(appConfig.port, Metrics.metricDefinitions)

    ActorSystem(
      KafkaClusterManager.init(appConfig, endpointCreator, clientCreator), "kafkalagexporterapp")
  }
}
