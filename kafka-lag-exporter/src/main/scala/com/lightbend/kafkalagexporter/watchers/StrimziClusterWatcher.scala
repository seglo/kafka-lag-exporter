package com.lightbend.kafkalagexporter.watchers

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, PostStop}
import com.lightbend.kafkaclientmetrics.KafkaCluster
import com.lightbend.kafkalagexporter.KafkaClusterManager

object StrimziClusterWatcher {
  val name: String = "strimzi"

  def init(handler: ActorRef[KafkaClusterManager.Message]): Behavior[Watcher.Message] = Behaviors.setup { context =>
    val watcher = new Watcher.Events {
      override def added(cluster: KafkaCluster): Unit = handler ! KafkaClusterManager.ClusterAdded(cluster)
      override def removed(cluster: KafkaCluster): Unit = handler ! KafkaClusterManager.ClusterAdded(cluster)
      override def error(e: Throwable): Unit = context.log.error(e, e.getMessage)
    }
    val client = StrimziClient(watcher)
    watch(client)
  }

  def watch(client: Watcher.Client): Behaviors.Receive[Watcher.Message] = Behaviors.receive {
    case (context, _: Watcher.Stop) =>
      Behaviors.stopped {
        Behaviors.receiveSignal {
          case (_, PostStop) =>
            client.close()
            context.log.info("Gracefully stopped StrimziKafkaWatcher")
            Behaviors.same
        }
      }
  }
}
