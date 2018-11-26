package com.lightbend.kafkalagexporter

import java.time.Instant

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import com.lightbend.kafkalagexporter.Offsets.{ConsumerGroup, Double, GroupTopicPartition, LagMetric, LastCommittedOffsets, LatestOffsets, Measurement}
import com.lightbend.kafkalagexporter.Protocol.{Collect, Message, NewOffsets}

import scala.concurrent.Future
import scala.util.{Failure, Success}

object ConsumerGroupCollector {
  def collector(appConfig: AppConfig,
                clientCreator: () => KafkaClientContract,
                latestOffsets: Offsets.LatestOffsets,
                lastCommittedOffsets: Offsets.LastCommittedOffsets): Behavior[Message] = Behaviors.receive {
    case (context, _: Collect) =>
      implicit val ec = context.executionContext

      val client = clientCreator()

      def getLatestAndGroupOffsets(groups: List[ConsumerGroup]): Future[NewOffsets] = {
        val now = Instant.now().toEpochMilli
        val groupOffsetsF = client.getGroupOffsets(groups)
        val latestOffsetsF = client.getLatestOffsets(groups)

        for {
          groupOffsets <- groupOffsetsF
          latestOffsets <- latestOffsetsF
        } yield
          NewOffsets(now, latestOffsets, groupOffsets)
      }

      val f = for {
        groups <- client.getGroups()
        newOffsets <- getLatestAndGroupOffsets(groups)
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
        (consumerGroup, offsetsMap) <- newOffsets.lastGroupOffsets
        (topicPartition, offset) <- offsetsMap
      } yield {
        val gtp = GroupTopicPartition(consumerGroup, topicPartition)
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

      context.scheduleOnce(appConfig.pollInterval, context.self, Collect)

      collector(appConfig, clientCreator, LatestOffsets(newOffsets.latestOffsets), LastCommittedOffsets(updatedLastCommittedOffsets))
  }
}
