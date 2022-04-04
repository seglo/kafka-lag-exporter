/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2022 Sean Glover <https://seanglover.com>
 */

package com.lightbend.kafkalagexporter.integration.minikube

import akka.kafka.testkit.scaladsl.KafkaSpec
import com.lightbend.kafkalagexporter.integration.PrometheusUtils
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j.{Logger, LoggerFactory}

import scala.sys.process._
import scala.util.Random

abstract class MinikubeSpecBase
    extends KafkaSpec(-1)
    with AnyWordSpecLike
    with BeforeAndAfterAll
    with Matchers
    with Eventually
    with PrometheusUtils {

  private[this] val log: Logger = LoggerFactory.getLogger(getClass)
  override val bootstrapServers: String =
    getNodePortForService("strimzi-kafka-cluster-kafka-external-bootstrap")

  override def beforeAll(): Unit = {
    setUp()
    install()
  }

  override def afterAll(): Unit = {
    cleanUp()
    uninstall()
  }

  def rnd: String = Random.alphanumeric.take(5).mkString

  override def createGroupId(suffix: Int): String = s"group-$suffix-$rnd"
  override def createTopicName(suffix: Int): String = s"topic-$suffix-$rnd"

  def exporterHostPort: String = getNodePortForService(
    "kafka-lag-exporter-service"
  )

  def getNodePortForService(service: String): String = {
    val nodePort =
      s"./examples/k8s/scripts/get_nodeport_for_service.sh $service".!!.trim
    log.info(s"NodePort host and port for service '$service': $nodePort")
    nodePort
  }

  def install(): Unit =
    "helm install kafka-lag-exporter ./charts/kafka-lag-exporter --values ./examples/k8s/kafka-lag-exporter-helm-values.yaml".!

  def uninstall(): Unit = "helm uninstall kafka-lag-exporter".!
}
