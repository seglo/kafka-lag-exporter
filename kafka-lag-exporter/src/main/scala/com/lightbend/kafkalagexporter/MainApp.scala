package com.lightbend.kafkalagexporter

import java.util.concurrent.Executors

import akka.actor.typed.ActorSystem
import com.lightbend.kafkaclientmetrics.{KafkaClient, PrometheusEndpoint}
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object MainApp extends App {
  // Cached thread pool for various Kafka calls for non-blocking I/O
  val kafkaClientEc = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  val appConfig = AppConfig(ConfigFactory.load().getConfig("kafka-lag-exporter"))

  val clientCreator = (bootstrapBrokers: String) => KafkaClient(bootstrapBrokers, appConfig.clientGroupId)(kafkaClientEc)
  val endpointCreator = () => PrometheusEndpoint(appConfig.port, Metrics.metricDefinitions)

  val system = ActorSystem(
    KafkaClusterManager.init(appConfig, endpointCreator, clientCreator), "kafkalagexporterapp")

  // Add shutdown hook to respond to SIGTERM and gracefully shutdown the actor system
  sys.ShutdownHookThread {
    system ! KafkaClusterManager.Stop
    Await.result(system.whenTerminated, 5 seconds)
  }
}
