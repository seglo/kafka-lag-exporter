/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter.integration

import akka.actor.typed.ActorSystem
import akka.kafka.testkit.scaladsl.{EmbeddedKafkaLike, ScalatestKafkaSpec}
import com.lightbend.kafkalagexporter.MainApp
import com.lightbend.kafkalagexporter.KafkaClusterManager
import com.typesafe.config.{Config, ConfigFactory}
import net.manub.embeddedkafka.EmbeddedKafkaConfig
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpecLike}

import scala.concurrent.Await
import scala.concurrent.duration._

abstract class SpecBase(kafkaPort: Int, val exporterPort: Int)
  extends ScalatestKafkaSpec(kafkaPort)
    with WordSpecLike
    with BeforeAndAfterEach
    with EmbeddedKafkaLike
    with Matchers
    with ScalaFutures
    with Eventually
    with PrometheusUtils
    with LagSim {

  def createKafkaConfig: EmbeddedKafkaConfig =
    EmbeddedKafkaConfig(kafkaPort,
      zooKeeperPort,
      Map(
        "offsets.topic.replication.factor" -> "1"
      ))

  var kafkaLagExporter: ActorSystem[KafkaClusterManager.Message] = _

  val clusterName = "default"

  val config: Config = ConfigFactory.parseString(s"""
                                            |kafka-lag-exporter {
                                            |  port: $exporterPort
                                            |  clusters = [
                                            |    {
                                            |      name: "$clusterName"
                                            |      bootstrap-brokers: "localhost:$kafkaPort"
                                            |    }
                                            |  ]
                                            |  poll-interval = 5 seconds
                                            |  lookup-table-size = 20
                                            |}""".stripMargin).withFallback(ConfigFactory.load())

  override def beforeEach(): Unit = {
    kafkaLagExporter = MainApp.start(config)
  }

  override def afterEach(): Unit = {
    kafkaLagExporter ! KafkaClusterManager.Stop
    Await.result(kafkaLagExporter.whenTerminated, 10 seconds)
  }
}
