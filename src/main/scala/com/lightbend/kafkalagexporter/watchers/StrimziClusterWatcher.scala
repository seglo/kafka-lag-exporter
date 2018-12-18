package com.lightbend.kafkalagexporter.watchers

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, PostStop}
import com.lightbend.kafkalagexporter.AppConfig.Cluster
import com.lightbend.kafkalagexporter.MainApp

object StrimziClusterWatcher {
  val name: String = "strimzi"

  def init(handler: ActorRef[MainApp.Message]): Behavior[Watcher.Message] = Behaviors.setup { context =>
    val watcher = new Watcher.Events {
      override def added(cluster: Cluster): Unit = handler ! MainApp.ClusterAdded(cluster)
      override def removed(cluster: Cluster): Unit = handler ! MainApp.ClusterAdded(cluster)
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
