/*
 * Copyright (C) 2019-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter.integration

import akka.actor.Cancellable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{Behavior, PostStop}
import akka.kafka.Subscriptions
import akka.kafka.scaladsl.Consumer
import akka.kafka.testkit.scaladsl.KafkaSpec
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Keep
import akka.stream.testkit.scaladsl.TestSink
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.Await
import scala.concurrent.duration._

trait LagSim extends KafkaSpec with ScalaFutures {
  private implicit val patience: PatienceConfig = PatienceConfig(30.seconds, 1.second)

  class LagSimulator(topic: String, group: String) {
    private var offset: Int = 0

    private lazy val (consumerControl, consumerProbe) = Consumer
      .committableSource(consumerDefaults.withGroupId(group), Subscriptions.topics(topic))
      .buffer(size = 1, OverflowStrategy.backpressure)
      .map { elem =>
        log.debug("Committing elem with offset: {}", elem.committableOffset.partitionOffset)
        elem.committableOffset.commitScaladsl()
      }
      .toMat(TestSink.probe)(Keep.both)
      .run()

    def produceElements(num: Int): Unit = {
      Await.result(produce(topic, offset to (offset + num)), remainingOrDefault)
      offset += num + 1
    }

    // TODO: Replace this with regular Kafka Consumer for more fine-grained control over committing
    def consumeElements(num: Int): Unit = {
      consumerProbe
        .request(num)
        .expectNextN(num)
    }

    def shutdown(): Unit = {
      consumerControl.shutdown().futureValue
      consumerProbe.cancel()
    }
  }

  sealed trait Simulator
  case class Tick(produce: Int, consume: Int) extends Simulator

  def lagSimActor(simulator: LagSimulator,
                  scheduledTick: Cancellable = Cancellable.alreadyCancelled): Behavior[Simulator] =
    Behaviors.receive[Simulator] {
      case (context, tick @ Tick(produce, consume)) =>
        simulator.produceElements(produce)
        simulator.consumeElements(consume)
        lagSimActor(simulator, context.scheduleOnce(1 second, context.self, tick))
    } receiveSignal {
      case (_, PostStop) =>
        simulator.shutdown()
        scheduledTick.cancel()
        Behaviors.same
    }

}
