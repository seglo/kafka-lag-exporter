/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2022 Sean Glover <https://seanglover.com>
 */

package com.lightbend.kafkalagexporter.integration

import akka.actor.typed.ActorSystem
import akka.kafka.testkit.KafkaTestkitTestcontainersSettings
import akka.kafka.testkit.scaladsl.{ScalatestKafkaSpec, TestcontainersKafkaPerClassLike}
import com.lightbend.kafkalagexporter.{KafkaClusterManager, MainApp}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.Await
import scala.concurrent.duration._

abstract class LocalSpecBase(val exporterPort: Int)
  extends ScalatestKafkaSpec(-1)
    with AnyWordSpecLike
    with BeforeAndAfterEach
    with TestcontainersKafkaPerClassLike
    with Eventually
    with PrometheusUtils {

  private[this] val log: Logger = LoggerFactory.getLogger(getClass)

  override val testcontainersSettings = KafkaTestkitTestcontainersSettings(system)
    .withConfigureKafka { brokerContainers =>
      brokerContainers.foreach(_.withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1"))
    }

  var kafkaLagExporter: ActorSystem[KafkaClusterManager.Message] = _

  val clusterName = "default"

  val exporterHostPort = s"localhost:$exporterPort"

  def config: Config = ConfigFactory.parseString(s"""
                                            |kafka-lag-exporter {
                                            |  reporters.prometheus.port = $exporterPort
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
    Await.result(kafkaLagExporter.whenTerminated, 15 seconds)
  }
}
