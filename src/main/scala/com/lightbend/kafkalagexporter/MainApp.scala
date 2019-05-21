/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import java.util.concurrent.Executors

import akka.actor.typed.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object MainApp extends App {
  val system = start()

  // Add shutdown hook to respond to SIGTERM and gracefully shutdown the actor system
  sys.ShutdownHookThread {
    system ! KafkaClusterManager.Stop
    Await.result(system.whenTerminated, 10 seconds)
  }

  def start(config: Config = ConfigFactory.load()): ActorSystem[KafkaClusterManager.Message] = {
    // Cached thread pool for various Kafka calls for non-blocking I/O
    val kafkaClientEc = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

    val appConfig = AppConfig(config)

    val clientCreator = (cluster: KafkaCluster) =>
      KafkaClient(cluster, appConfig.clientGroupId, appConfig.clientTimeout)(kafkaClientEc)
    val endpointCreator = () => PrometheusEndpointSink(appConfig.port, Metrics.metricDefinitions)

    ActorSystem(
      KafkaClusterManager.init(appConfig, endpointCreator, clientCreator), "kafka-lag-exporter")
  }
}
