package com.lightbend.kafkalagexporter

import java.util.concurrent.Executors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Behavior}
import com.lightbend.kafkalagexporter.Protocol.{Collect, Message}
import com.typesafe.config.ConfigFactory

import scala.concurrent.ExecutionContext


object MainApp extends App {
  // Cached thread pool for various Kafka calls for non-blocking I/O
  val kafkaClientEc = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  val appConfig = AppConfig(ConfigFactory.load().getConfig("kafka-lag-exporter"))

  val clientCreator = () => KafkaClient(appConfig.bootstrapBrokers)(kafkaClientEc)

  val main: Behavior[Message] =
    Behaviors.setup { context =>
      val lastCommittedOffsets = Offsets.LastCommittedOffsets()
      val latestOffsets = Offsets.LatestOffsets()
      val collector = context.spawn(ConsumerGroupCollector.collector(appConfig, clientCreator, latestOffsets, lastCommittedOffsets), "collector")

      Behaviors.receiveMessage { _ =>
        collector ! Collect
        Behaviors.same
      }
    }

  val system: ActorSystem[Message] = ActorSystem(main, "kafkalagexporterapp")

  system ! Collect
}
