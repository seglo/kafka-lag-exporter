package com.lightbend.kafkalagexporter

import akka.actor.Cancellable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, PostStop}
import com.lightbend.kafkalagexporter.KafkaClient.KafkaClientContract

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

object ConsumerGroupCollector {
  import com.lightbend.kafkalagexporter.Domain._

  sealed trait Message
  sealed trait Collect extends Message
  final case object Collect extends Collect
  sealed trait Stop extends Message
  final case object Stop extends Stop
  final case class NewOffsets(latestOffsets: LatestOffsets, lastGroupOffsets: LastCommittedOffsets) extends Message

  final case class CollectorConfig(pollInterval: FiniteDuration, clusterName: String, clusterBootstrapBrokers: String)

  final case class CollectorState(
                                   latestOffsets: Domain.LatestOffsets = Domain.LatestOffsets(),
                                   lastGroupOffsets: Domain.LastCommittedOffsets = Domain.LastCommittedOffsets(),
                                   scheduledCollect: Cancellable = Cancellable.alreadyCancelled
                                 )

  def init(config: CollectorConfig,
           clientCreator: String => KafkaClientContract,
           reporter: ActorRef[LagReporter.Message]): Behavior[ConsumerGroupCollector.Message] = Behaviors.setup { _ =>
    val collectorState = CollectorState()
    collector(config, clientCreator(config.clusterBootstrapBrokers), reporter, collectorState)
  }

  def collector(config: CollectorConfig,
                client: KafkaClientContract,
                reporter: ActorRef[LagReporter.Message],
                state: CollectorState): Behavior[Message] = Behaviors.receive {

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

      context.log.debug("Collecting offsets")

      val f = for {
        groups <- client.getGroups()
        newOffsets <- getLatestAndGroupOffsets(groups)
      } yield newOffsets

      f.onComplete {
        case Success(newOffsets) =>
          context.self ! newOffsets
        case Failure(ex) =>
          context.log.error(ex, "An error occurred while retrieving offsets")
          context.self ! Stop
      }(ec)

      Behaviors.same
    case (context, newOffsets: NewOffsets) =>
      val updatedLastCommittedOffsets = mergeLastGroupOffsets(state.lastGroupOffsets, newOffsets)

      context.log.debug("Reporting offsets")

      reportLatestOffsetMetrics(config, reporter, newOffsets)
      reportConsumerGroupMetrics(config, reporter, newOffsets, updatedLastCommittedOffsets)

      context.log.debug("Polling in {}", config.pollInterval)
      val scheduledCollect = context.scheduleOnce(config.pollInterval, context.self, Collect)

      val newState = state.copy(
        latestOffsets = newOffsets.latestOffsets,
        lastGroupOffsets = updatedLastCommittedOffsets,
        scheduledCollect = scheduledCollect
      )

      collector(config, client, reporter, newState)
    case (context, _: Stop) =>
      state.scheduledCollect.cancel()
      Behaviors.stopped {
        Behaviors.receiveSignal {
          case (_, PostStop) =>
            client.close()
            context.log.info("Gracefully stopped polling and Kafka client for cluster: {}", config.clusterName)
            Behaviors.same
        }
      }
  }

  private final case class GroupPartitionLag(gtp: GroupTopicPartition, offsetLag: Long, timeLag: FiniteDuration)

  private def reportConsumerGroupMetrics(
                                          config: CollectorConfig,
                                          reporter: ActorRef[LagReporter.Message],
                                          newOffsets: NewOffsets,
                                          updatedLastCommittedOffsets: Map[GroupTopicPartition, Measurements.Measurement]
                                        ): Unit = {
    val groupLag = for {
      (gtp, measurement: Measurements.Double) <- updatedLastCommittedOffsets
      member <- gtp.group.members.find(_.partitions.contains(gtp.topicPartition))
      latestOffset: Measurements.Single <- newOffsets.latestOffsets.get(gtp.topicPartition)
    } yield {
      val offsetLag = measurement.offsetLag(latestOffset.offset)
      val timeLag = measurement.timeLag(latestOffset.offset)

      reporter ! LagReporter.LastGroupOffsetMetric(config.clusterName, gtp, member, measurement.b.offset)
      reporter ! LagReporter.OffsetLagMetric(config.clusterName, gtp, member, measurement.offsetLag(latestOffset.offset))
      reporter ! LagReporter.TimeLagMetric(config.clusterName, gtp, member, measurement.timeLag(latestOffset.offset))

      GroupPartitionLag(gtp, offsetLag, timeLag)
    }

    for {
      (group, values) <- groupLag.groupBy(_.gtp.group)
    } {
      val maxOffsetLag = values.maxBy(_.offsetLag)
      val maxTimeLag = values.maxBy(_.timeLag)

      reporter ! LagReporter.MaxGroupOffsetLagMetric(config.clusterName, group, maxOffsetLag.offsetLag)
      reporter ! LagReporter.MaxGroupTimeLagMetric(config.clusterName, group, maxTimeLag.timeLag)
    }
  }

  private def reportLatestOffsetMetrics(
                                         config: CollectorConfig,
                                         reporter: ActorRef[LagReporter.Message],
                                         newOffsets: NewOffsets
                                       ): Unit = {
    for ((tp, measurement) <- newOffsets.latestOffsets)
      reporter ! LagReporter.LatestOffsetMetric(config.clusterName, tp, measurement.offset)
  }

  private def mergeLastGroupOffsets(
                                     lastGroupOffsets: LastCommittedOffsets,
                                     newOffsets: NewOffsets): Map[GroupTopicPartition, Measurements.Measurement] = {
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
