/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2022 Sean Glover <https://seanglover.com>
 */

package com.lightbend.kafkalagexporter

import java.util.concurrent.Executors

import akka.actor.typed.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}
import io.prometheus.client.CollectorRegistry

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object MainApp extends App {
  val system = start()

  // Add shutdown hook to respond to SIGTERM and gracefully shutdown the actor system
  sys.ShutdownHookThread {
    system ! KafkaClusterManager.Stop
    Await.result(system.whenTerminated, 10 seconds)
  }

  def start(
      config: Config = ConfigFactory.load()
  ): ActorSystem[KafkaClusterManager.Message] = {
    // Cached thread pool for various Kafka calls for non-blocking I/O
    val kafkaClientEc =
      ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

    val appConfig = AppConfig(config)

    val clientCreator = (cluster: KafkaCluster) =>
      KafkaClient(
        cluster,
        appConfig.clientGroupId,
        appConfig.clientTimeout,
        appConfig.retries
      )(
        kafkaClientEc
      )

    var endpointCreators: List[KafkaClusterManager.NamedCreator] = List()

    appConfig.sinkConfigs.foreach { sinkConfig =>
      val endpointCreator = sinkConfig.sinkType match {
        case "PrometheusEndpointSink" =>
          KafkaClusterManager.NamedCreator(
            "prometheus-lag-reporter",
            (
                () =>
                  PrometheusEndpointSink(
                    sinkConfig.asInstanceOf[PrometheusEndpointSinkConfig],
                    Metrics.definitions,
                    appConfig.clustersGlobalLabels(),
                    CollectorRegistry.defaultRegistry
                  )
            )
          )
        case "InfluxDBPusherSink" =>
          KafkaClusterManager.NamedCreator(
            "influxDB-lag-reporter",
            (
                () =>
                  InfluxDBPusherSink(
                    sinkConfig.asInstanceOf[InfluxDBPusherSinkConfig],
                    appConfig.clustersGlobalLabels()
                  )
            )
          )
        case "InfluxDB2PusherSink" =>
          KafkaClusterManager.NamedCreator(
            "influxDB2-lag-reporter",
            (
              () =>
                InfluxDB2PusherSink(
                  sinkConfig.asInstanceOf[InfluxDB2PusherSinkConfig],
                  appConfig.clustersGlobalLabels()
                )
              )
          )
        case "GraphiteEndpointSink" =>
          KafkaClusterManager.NamedCreator(
            "graphite-lag-reporter",
            (
                () =>
                  GraphiteEndpointSink(
                    sinkConfig.asInstanceOf[GraphiteEndpointConfig],
                    appConfig.clustersGlobalLabels()
                  )
            )
          )
      }
      endpointCreators = endpointCreator :: endpointCreators
    }

    ActorSystem(
      KafkaClusterManager.init(appConfig, endpointCreators, clientCreator),
      "kafka-lag-exporter"
    )
  }
}
