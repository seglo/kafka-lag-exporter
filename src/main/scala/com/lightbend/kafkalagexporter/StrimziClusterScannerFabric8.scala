package com.lightbend.kafkalagexporter

import java.util.concurrent.{CountDownLatch, TimeUnit}

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.fabric8.kubernetes.api.builder.{Function => Fabric8Function}
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinitionBuilder
import io.fabric8.kubernetes.client._
import io.fabric8.kubernetes.internal.KubernetesDeserializer

import scala.collection.JavaConverters._

object StrimziClusterScannerFabric8 {

  /**
    * Strimzi Kafka CRD declaration for Fabric8 CRD SerDes
    *
    * Notes
    * - Ignore all custom fields (i.e. the spec) of the Kafka resource. We only care about its metadata.
    */
  @JsonIgnoreProperties(ignoreUnknown = true)
  class KafkaResource extends CustomResource
  object KafkaResource {
    val name = "kafkas.kafka.strimzi.io"
    val kind = "Kafka"
    val shortNames = "k"
    val plural = "kafkas"
    val group = "kafka.strimzi.io"
    val groupVersion = s"$group/v1alpha1"
  }
  class KafkaResourceList extends CustomResourceList[KafkaResource]
  class KafkaResourceDoneable(val resource: KafkaResource, val function: Fabric8Function[KafkaResource, KafkaResource])
    extends CustomResourceDoneable[KafkaResource](resource, function)

  case class StrimziCluster(name: String, namespace: String) {
    val bootstrapBrokerHost: String = s"$name-kafka-bootstrap.$namespace:9094"
  }
}

class StrimziClusterScannerFabric8 {

  import StrimziClusterScannerFabric8._

  val client = new DefaultKubernetesClient()

  val kafkaDefinition = new CustomResourceDefinitionBuilder()
    .withApiVersion(KafkaResource.groupVersion)
    .withNewMetadata().withName(KafkaResource.name).endMetadata()
    .withNewSpec().withGroup(KafkaResource.group).withVersion(KafkaResource.groupVersion).withScope("Cluster")//.withScope("Namespaced")
    .withNewNames().withKind(KafkaResource.kind).withShortNames(KafkaResource.shortNames).withPlural(KafkaResource.plural).endNames().endSpec()
    .build()

  KubernetesDeserializer.registerCustomKind(KafkaResource.groupVersion, KafkaResource.kind, classOf[KafkaResource])

  val kafkaClient =
    client.customResources(kafkaDefinition, classOf[KafkaResource], classOf[KafkaResourceList], classOf[KafkaResourceDoneable]).inAnyNamespace()

  def scan(): List[StrimziCluster] = {
    kafkaClient.list().getItems.asScala.map { item =>
      val metadata = item.getMetadata
      StrimziCluster(metadata.getName, metadata.getNamespace)
    }.toList
  }

  def watch() = {
    val closeLatch = new CountDownLatch(1)
    try {
      val watch = kafkaClient.watch(new Watcher[KafkaResource]() {
        override def eventReceived(action: Watcher.Action, resource: KafkaResource): Unit = {
          println(s"$action: ${resource.getMetadata}")
        }

        override def onClose(e: KubernetesClientException): Unit = {
          println("Watcher onClose")
          if (e != null) {
            println(e)
            closeLatch.countDown()
          }
        }
      })
      try
        closeLatch.await(60, TimeUnit.SECONDS)
      catch {
        case e@(_: KubernetesClientException | _: InterruptedException) =>
          println(s"Could not watch resources: $e")
      } finally if (watch != null) watch.close()
    }
    catch {
      case e: Exception =>
        e.printStackTrace()
        println(s"Could not watch resources: $e")
        val suppressed = e.getSuppressed
        if (suppressed != null) for (t <- suppressed) {
          println(s"Could not watch resources: $t")
        }
    } finally if (client != null) client.close()
    Thread.sleep(60000l)
  }
}
