package com.lightbend.kafka.kafkalagexporter.integration
import akka.actor.Cancellable
import akka.actor.typed.{Behavior, PostStop}
import akka.actor.typed.scaladsl.Behaviors
import akka.kafka.Subscriptions
import akka.kafka.scaladsl.Consumer
import akka.kafka.testkit.internal.KafkaTestKit
import akka.kafka.testkit.scaladsl.{EmbeddedKafkaLike, KafkaSpec, ScalatestKafkaSpec}
import akka.stream.scaladsl.Keep
import akka.stream.testkit.scaladsl.TestSink
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.Await
import scala.concurrent.duration._

trait KafkaAppSimulator extends KafkaSpec with ScalaFutures {
  private implicit val patience: PatienceConfig = PatienceConfig(30 seconds, 1 second)

  class AppSimulator(topic: String, group: String) {

    private var offset: Int = 0
    var scheduledTick: Cancellable = Cancellable.alreadyCancelled

    private lazy val (consumerControl, consumerProbe) = Consumer
      .committableSource(consumerDefaults.withGroupId(group), Subscriptions.topics(topic))
      .filterNot(_.record.value == InitialMsg)
      .map { elem =>
        elem.committableOffset.commitScaladsl()
        log.debug("Committed offset: {}", elem.committableOffset.partitionOffset)
        elem
      }
      .toMat(TestSink.probe)(Keep.both)
      .run()

    def produceElements(num: Int): Unit = {
      Await.result(produce(topic, offset to (offset + num)), remainingOrDefault)
      offset += num + 1
    }

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

  def appSimulatorActor(simulator: AppSimulator,
                        scheduledTick: Cancellable = Cancellable.alreadyCancelled): Behavior[Simulator] =
    Behaviors.receive[Simulator] {
      case (context, tick @ Tick(produce, consume)) =>
        simulator.produceElements(produce)
        simulator.consumeElements(consume)
        appSimulatorActor(simulator, context.scheduleOnce(1 second, context.self, tick))
    } receiveSignal {
      case (_, PostStop) =>
        simulator.shutdown()
        scheduledTick.cancel()
        Behaviors.same
    }

}
