/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import java.util.concurrent.Executors

import akka.actor.typed.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.HTTPServer

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
    val server = new HTTPServer(appConfig.port)
    val endpointCreator = () => PrometheusEndpointSink(Metrics.definitions, appConfig.metricWhitelist,
      appConfig.clustersGlobalLabels(), server, CollectorRegistry.defaultRegistry)

    ActorSystem(
      KafkaClusterManager.init(appConfig, endpointCreator, clientCreator), "kafka-lag-exporter")
  }
}
