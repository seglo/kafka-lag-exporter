package com.lightbend.kafkalagexporter

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import com.lightbend.kafkalagexporter.KafkaClient.KafkaClientContract

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

object ConsumerGroupCollector {
  import com.lightbend.kafkalagexporter.Domain._

  sealed trait Message
  sealed trait Collect extends Message
  case object Collect extends Collect
  case class NewOffsets(latestOffsets: LatestOffsets, lastGroupOffsets: LastCommittedOffsets) extends Message

  def init(appConfig: AppConfig,
           clientCreator: () => KafkaClientContract,
           reporter: ActorRef[LagReporter.Message]): Behavior[ConsumerGroupCollector.Message] = Behaviors.setup { _ =>
    val lastCommittedOffsets = Domain.LastCommittedOffsets()
    val latestOffsets = Domain.LatestOffsets()

    collector(appConfig, clientCreator, latestOffsets, lastCommittedOffsets, reporter)
  }

  def collector(appConfig: AppConfig,
                clientCreator: () => KafkaClientContract,
                latestOffsets: Domain.LatestOffsets,
                lastGroupOffsets: Domain.LastCommittedOffsets,
                reporter: ActorRef[LagReporter.Message]): Behavior[Message] = Behaviors.receive {

    case (context, _: Collect) =>
      implicit val ec: ExecutionContextExecutor = context.executionContext

      val client = clientCreator()

      def getLatestAndGroupOffsets(groups: List[ConsumerGroup]): Future[NewOffsets] = {
        val groupOffsetsF = client.getGroupOffsets(groups)
        val latestOffsetsF = client.getLatestOffsets(groups)

        for {
          groupOffsets <- groupOffsetsF
          latestOffsets <- latestOffsetsF
        } yield NewOffsets(latestOffsets, groupOffsets)
      }

      val f = for {
        groups <- client.getGroups()
        newOffsets <- getLatestAndGroupOffsets(groups)
      } yield newOffsets

      f.onComplete {
        case Success(newOffsets) =>
          client.close()
          context.self ! newOffsets
        case Failure(ex) =>
          println(s"An error occurred while retrieving offsets: $ex")
          throw ex
      }(ec)

      Behaviors.same
    case (context, newOffsets: NewOffsets) =>
      val updatedLastCommittedOffsets = mergeLastGroupOffsets(lastGroupOffsets, newOffsets)

      for((tp, measurement) <- newOffsets.latestOffsets)
        reporter ! LagReporter.LatestOffsetMetric(tp, measurement.offset)

      for {
        (gtp, measurement: Measurements.Double) <- updatedLastCommittedOffsets
        member <- gtp.group.members.find(_.partitions.contains(gtp.topicPartition))
        latestOffset: Measurements.Single <- newOffsets.latestOffsets.get(gtp.topicPartition)
      } {
        reporter ! LagReporter.LastGroupOffsetMetric(gtp, member, measurement.b.offset)
        reporter ! LagReporter.OffsetLagMetric(gtp, member, measurement.offsetLag(latestOffset.offset))
        reporter ! LagReporter.TimeLagMetric(gtp, member, measurement.lag(latestOffset.offset))
      }

      context.scheduleOnce(appConfig.pollInterval, context.self, Collect)

      collector(appConfig, clientCreator, newOffsets.latestOffsets, updatedLastCommittedOffsets, reporter)
  }

  private def mergeLastGroupOffsets(lastGroupOffsets: LastCommittedOffsets, newOffsets: NewOffsets): Map[GroupTopicPartition, Measurements.Measurement] = {
    for {
      (groupTopicPartition, newMeasurement: Measurements.Single) <- newOffsets.lastGroupOffsets
    } yield {
      groupTopicPartition -> lastGroupOffsets
        .get(groupTopicPartition)
        .map(measurement => measurement.addMeasurement(newMeasurement))
        .getOrElse(newMeasurement)
    }
  }
}
