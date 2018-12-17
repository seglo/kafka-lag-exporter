package com.lightbend.kafkalagexporter.watchers

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import com.lightbend.kafkalagexporter.{AppConfig, Cluster}

object Watcher {

  sealed trait Message
  sealed trait Stop extends Message
  final case object Stop extends Stop

  trait Client {
    def close(): Unit
  }

  trait Events {
    def added(cluster: Cluster): Unit
    def removed(cluster: Cluster): Unit
    def error(e: Throwable): Unit
  }

  def createClusterWatchers(context: ActorContext[com.lightbend.kafkalagexporter.MainApp.Message],
                            appConfig: AppConfig): Seq[ActorRef[Watcher.Message]] = {
    // Add additional watchers here..
    val configMap = Seq(StrimziClusterWatcher.name -> appConfig.strimziWatcher)
    configMap.flatMap {
      case (name, enabled) if name == StrimziClusterWatcher.name && enabled =>
        context.log.info(s"Adding watcher: $name")
        Seq(context.spawn(StrimziClusterWatcher.init(context.self), s"strimzi-cluster-watcher-$name"))
      case _ => Seq()
    }
  }
}