package com.lightbend.kafkalagexporter.watchers

import java.lang

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lightbend.kafkalagexporter.AppConfig.Cluster
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinitionBuilder
import io.fabric8.kubernetes.client.{Watcher => FWatcher, _}
import io.fabric8.kubernetes.client.dsl.FilterWatchListMultiDeletable
import io.fabric8.kubernetes.internal.KubernetesDeserializer
import io.fabric8.kubernetes.api.builder.{Function => Fabric8Function}

import scala.util.control.NonFatal

object StrimziClient {
  def apply(clusterWatcher: Watcher.Events): Watcher.Client = new StrimziClient(clusterWatcher)

  // Ignore all custom fields (i.e. the spec) of the Kafka resource. We only care about its metadata.
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

  KubernetesDeserializer.registerCustomKind(KafkaResource.groupVersion, KafkaResource.kind, classOf[KafkaResource])

  private val kafkaDefinition = new CustomResourceDefinitionBuilder()
    .withApiVersion(KafkaResource.groupVersion)
    .withNewMetadata().withName(KafkaResource.name).endMetadata()
    .withNewSpec().withGroup(KafkaResource.group).withVersion(KafkaResource.groupVersion).withScope("Cluster")//.withScope("Namespaced")
    .withNewNames().withKind(KafkaResource.kind).withShortNames(KafkaResource.shortNames).withPlural(KafkaResource.plural).endNames().endSpec()
    .build()

  def createClient(client: DefaultKubernetesClient): FilterWatchListMultiDeletable[KafkaResource, KafkaResourceList, lang.Boolean, Watch, FWatcher[KafkaResource]] = {
    client.customResources(kafkaDefinition, classOf[KafkaResource], classOf[KafkaResourceList], classOf[KafkaResourceDoneable]).inAnyNamespace()
  }
}

class StrimziClient(clusterWatcher: Watcher.Events) extends Watcher.Client {
  import StrimziClient._

  private val k8sClient: DefaultKubernetesClient = new DefaultKubernetesClient()
  private val client: FilterWatchListMultiDeletable[KafkaResource, KafkaResourceList, lang.Boolean, Watch, FWatcher[KafkaResource]] =
    createClient(k8sClient)

  private def resourceToCluster(resource: KafkaResource): Cluster = {
    val name = resource.getMetadata.getName
    val namespace = resource.getMetadata.getNamespace
    val bootstrapBrokerHost: String = s"$name-kafka-bootstrap.$namespace:9092"
    Cluster(resource.getMetadata.getName, bootstrapBrokerHost)
  }

  def close(): Unit = k8sClient.close()

  try {
    client.watch(new FWatcher[KafkaResource]() {

      override def eventReceived(action: FWatcher.Action, resource: KafkaResource): Unit = action match {
        case FWatcher.Action.ADDED => clusterWatcher.added(resourceToCluster(resource))
        case FWatcher.Action.DELETED => clusterWatcher.removed(resourceToCluster(resource))
        case _ => throw new Exception(s"Unhandled Watcher.Action: $action: ${resource.getMetadata}")
      }

      override def onClose(e: KubernetesClientException): Unit = {
        if (e != null) {
          clusterWatcher.error(e)
        }
      }
    })
  } catch {
    case NonFatal(e) => clusterWatcher.error(e)
  }
}
