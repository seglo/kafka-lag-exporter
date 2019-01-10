package com.lightbend.kafkalagexporter.util

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.scaladsl.Behaviors.Receive

object AkkaTypedUtils {
  /**
    * Log typed message to DEBUG and passthrough to receive handler.
    */
  def loggingReceive[T](onMessage: PartialFunction[(ActorContext[T], T), Behavior[T]]): Receive[T] =
    Behaviors.receive[T] { (ctx, t) ⇒
      ctx.log.debug(s"Received: $t")
      onMessage.applyOrElse((ctx, t), (_: (ActorContext[T], T)) ⇒ Behaviors.unhandled[T])
    }
}
