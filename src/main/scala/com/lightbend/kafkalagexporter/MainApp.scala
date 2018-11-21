package com.lightbend.kafkalagexporter

import java.time.Instant
import java.util.concurrent.Executors

import akka.actor.Actor
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Behavior}
import akka.actor.typed.scaladsl.AskPattern._
import akka.dispatch.ExecutionContexts
import com.lightbend.kafkalagexporter.Offsets._
import com.lightbend.kafkalagexporter.Protocol.{Collect, Message, NewOffsets}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}


object MainApp extends App {
  val brokers = "localhost:9094"

  // Cached thread pool for various Kafka calls, best for non-blocking I/O
  val kafkaClientEc = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())
  val client = () =>
    KafkaClient(brokers)(kafkaClientEc)

  val main: Behavior[Message] =
    Behaviors.setup { context =>
      val lastCommittedOffsets = Offsets.LastCommittedOffsets()
      val latestOffsets = Offsets.LatestOffsets()
      val collector = context.spawn(ConsumerGroupCollector.collector(client, latestOffsets, lastCommittedOffsets), "collector")

      Behaviors.receiveMessage { message =>
        collector ! Collect
        Behaviors.same
      }
    }

  val system: ActorSystem[Message] = ActorSystem(main, "kafkalagexporterapp")

  system ! Collect
}

object Protocol {
  sealed trait Message
  sealed trait Collect extends Message
  case object Collect extends Collect
  case class NewOffsets(
                       now: Long,
                       latestOffsets: Map[Offsets.TopicPartition, Long],
                       lastGroupOffsets: Map[ConsumerGroupId, Map[Offsets.TopicPartition, Long]])
    extends Message
}

object FooActor {
  sealed trait MyMessage
  case object MyMessage extends MyMessage

  def bar(): Behavior[MyMessage] = Behaviors.receive {
    case (context, message: MyMessage) =>
      Behaviors.same
  }
}

object ConsumerGroupCollector {
  def collector(clientCreator: () => KafkaClientContract, latestOffsets: Offsets.LatestOffsets, lastCommittedOffsets: Offsets.LastCommittedOffsets): Behavior[Message] = Behaviors.receive {
    case (context, _: Collect) =>
      implicit val ec = context.executionContext

      val client = clientCreator()

      def getLatestAndGroupOffsets(groupIds: List[ConsumerGroupId]): Future[NewOffsets] = {
        val now = Instant.now().toEpochMilli
        val groupOffsetsF = client.getGroupOffsets(groupIds)
        val latestOffsetsF = client.getLatestOffsets(groupIds)

        for {
          groupOffsets <- groupOffsetsF
          latestOffsets <- latestOffsetsF
        } yield NewOffsets(now, latestOffsets, groupOffsets)
      }

      val f = for {
        groupIds <- client.getGroupIds()
        newOffsets <- getLatestAndGroupOffsets(groupIds)
      } yield newOffsets

      f.onComplete {
        case Success(newOffsets) =>
          client.close()
          context.self ! newOffsets
        case Failure(ex)         => println(s"An error occurred while retrieving offsets: $ex")
          throw ex
      }

      Behaviors.same
    case (context, newOffsets: NewOffsets) =>
      val updatedLastCommittedOffsets: Map[GroupTopicPartition, Measurement] = for {
        (consumerGroupId, offsetsMap) <- newOffsets.lastGroupOffsets
        (topicPartition, offset) <- offsetsMap
      } yield {
        val gtp = GroupTopicPartition(consumerGroupId, topicPartition)
        val newMeasurement = Offsets.Single(offset, newOffsets.now)
        gtp -> lastCommittedOffsets
          .get(gtp)
          .map(measurement => measurement.addMeasurement(newMeasurement))
          .getOrElse(newMeasurement)
      }

      val lagMetrics: Map[GroupTopicPartition, LagMetric] = for {
        (gtp, measurement: Double) <- updatedLastCommittedOffsets withFilter {
          case (_, _: Double) => true
          case _ => false
        }
        latestOffset: Long = newOffsets.latestOffsets.getOrElse(gtp.topicPartition, 0)
      } yield gtp -> Offsets.LagMetric(newOffsets.now, latestOffset, measurement)

      println(s"Lag metrics: ${lagMetrics}")

      context.scheduleOnce(5.seconds, context.self, Collect)

      collector(clientCreator, LatestOffsets(newOffsets.latestOffsets), LastCommittedOffsets(updatedLastCommittedOffsets))
  }
}


