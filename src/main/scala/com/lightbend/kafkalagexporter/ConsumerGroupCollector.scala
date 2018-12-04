package com.lightbend.kafkalagexporter

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import com.lightbend.kafkalagexporter.KafkaClient.KafkaClientContract

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

object ConsumerGroupCollector {
  import com.lightbend.kafkalagexporter.Domain._

  sealed trait Message
  sealed trait Collect extends Message
  case object Collect extends Collect
  sealed trait Stop extends Message
  case object Stop extends Stop
  case class NewOffsets(latestOffsets: LatestOffsets, lastGroupOffsets: LastCommittedOffsets) extends Message

  def init(appConfig: AppConfig,
           clientCreator: () => KafkaClientContract,
           reporter: ActorRef[LagReporter.Message]): Behavior[ConsumerGroupCollector.Message] = Behaviors.setup { _ =>
    val lastCommittedOffsets = Domain.LastCommittedOffsets()
    val latestOffsets = Domain.LatestOffsets()

    collector(appConfig, clientCreator(), latestOffsets, lastCommittedOffsets, reporter)
  }

  def collector(appConfig: AppConfig,
                client: KafkaClientContract,
                latestOffsets: Domain.LatestOffsets,
                lastGroupOffsets: Domain.LastCommittedOffsets,
                reporter: ActorRef[LagReporter.Message]): Behavior[Message] = Behaviors.receive {

    case (context, _: Collect) =>
      implicit val ec: ExecutionContextExecutor = context.executionContext

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
          context.self ! newOffsets
        case Failure(ex) =>
          context.log.error(ex, "An error occurred while retrieving offsets")
          context.scheduleOnce(appConfig.pollInterval, context.self, Collect)
      }(ec)

      Behaviors.same
    case (context, newOffsets: NewOffsets) =>
      val updatedLastCommittedOffsets = mergeLastGroupOffsets(lastGroupOffsets, newOffsets)

      reportLatestOffsetMetrics(reporter, newOffsets)

      reportConsumerGroupMetrics(reporter, newOffsets, updatedLastCommittedOffsets)

      context.log.debug("Polling in {}", appConfig.pollInterval)
      context.scheduleOnce(appConfig.pollInterval, context.self, Collect)

      collector(appConfig, client, newOffsets.latestOffsets, updatedLastCommittedOffsets, reporter)
    case (_, _: Stop) =>
      client.close()
      Behaviors.stopped
  }

  private case class GroupPartitionLag(gtp: GroupTopicPartition, offsetLag: Long, timeLag: FiniteDuration)

  private def reportConsumerGroupMetrics(reporter: ActorRef[LagReporter.Message], newOffsets: NewOffsets, updatedLastCommittedOffsets: Map[GroupTopicPartition, Measurements.Measurement]) = {
    val groupLag = for {
      (gtp, measurement: Measurements.Double) <- updatedLastCommittedOffsets
      member <- gtp.group.members.find(_.partitions.contains(gtp.topicPartition))
      latestOffset: Measurements.Single <- newOffsets.latestOffsets.get(gtp.topicPartition)
    } yield {
      val offsetLag = measurement.offsetLag(latestOffset.offset)
      val timeLag = measurement.timeLag(latestOffset.offset)

      reporter ! LagReporter.LastGroupOffsetMetric(gtp, member, measurement.b.offset)
      reporter ! LagReporter.OffsetLagMetric(gtp, member, measurement.offsetLag(latestOffset.offset))
      reporter ! LagReporter.TimeLagMetric(gtp, member, measurement.timeLag(latestOffset.offset))

      GroupPartitionLag(gtp, offsetLag, timeLag)
    }

    for {
      (group, values) <- groupLag.groupBy(_.gtp.group)
    } {
      val maxOffsetLag = values.maxBy(_.offsetLag)
      val maxTimeLag = values.maxBy(_.timeLag)

      reporter ! LagReporter.MaxGroupOffsetLagMetric(group, maxOffsetLag.offsetLag)
      reporter ! LagReporter.MaxGroupTimeLagMetric(group, maxTimeLag.timeLag)
    }
  }

  private def reportLatestOffsetMetrics(reporter: ActorRef[LagReporter.Message], newOffsets: NewOffsets) = {
    for ((tp, measurement) <- newOffsets.latestOffsets)
      reporter ! LagReporter.LatestOffsetMetric(tp, measurement.offset)
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
