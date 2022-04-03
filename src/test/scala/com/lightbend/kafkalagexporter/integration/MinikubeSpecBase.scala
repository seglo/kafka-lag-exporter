package com.lightbend.kafkalagexporter.integration

import akka.actor.typed.ActorSystem
import akka.kafka.testkit.KafkaTestkitTestcontainersSettings
import akka.kafka.testkit.scaladsl.{KafkaSpec, ScalatestKafkaSpec, TestcontainersKafkaPerClassLike}
import com.lightbend.kafkalagexporter.{KafkaClusterManager, MainApp}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.time._
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration._
import scala.util.Random
import sys.process._

abstract class MinikubeSpecBase
  extends KafkaSpec(-1)
    with AnyWordSpecLike
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with Matchers
    with ScalaFutures
    with Eventually
    with PrometheusUtils
    with LagSim {

  private[this] val log: Logger = LoggerFactory.getLogger(getClass)

  implicit val patience: PatienceConfig = PatienceConfig(30.seconds, 2.seconds)

  val rnd: String = Random.alphanumeric.take(5).mkString

  override def createGroupId(suffix: Int): String = s"group-$suffix-$rnd"
  override def createTopicName(suffix: Int): String = s"topic-$suffix-$rnd"

  override val bootstrapServers: String =
    getNodePortForService("strimzi-kafka-cluster-kafka-external-bootstrap")

  def exporterHostPort: String = getNodePortForService("kafka-lag-exporter-service")

  def waitForExporterService(): Unit = {
    val exp = timeout(Span(90, Seconds))
    eventually(exp)(scrape(exporterHostPort))
  }



//  private val topicCounter = new AtomicInteger()
//  private def nextNumber(): Int = topicCounter.incrementAndGet()
//  def createTopicName(suffix: Int): String = s"topic-$suffix-$nextNumber"
//
//  override def createTopic(): String = createTopic(0, 1, 1)
//
//  def createTopic(suffix: Int, partitions: Int, replication: Int): String = {
//    val topicName = createTopicName(suffix)
//    s"cat strimzi-topic-template.yaml | sed \"s/{{TOPIC_NAME}}/$topicName/g;s/{{PARTITIONS}}/$partitions/g;s/{{REPLICAS}}/$replication/g\" | kubectl apply -f -" !
//      topicName
//  }

  def getNodePortForService(service: String): String = {
    val nodePort = s"./examples/k8s/scripts/get_nodeport_for_service.sh $service".!!.trim
    log.info(s"NodePort host and port for service '$service': $nodePort")
    nodePort
  }

  def install(): Unit =
    "helm install kafka-lag-exporter ./charts/kafka-lag-exporter --values ./examples/k8s/kafka-lag-exporter-helm-values.yaml".!

  def uninstall(): Unit = "helm uninstall kafka-lag-exporter".!
}

