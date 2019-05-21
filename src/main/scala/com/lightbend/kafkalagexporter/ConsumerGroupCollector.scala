/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import java.time.Clock

import akka.actor.Cancellable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, PostStop, SupervisorStrategy}
import com.lightbend.kafkalagexporter.KafkaClient.KafkaClientContract
import com.lightbend.kafkalagexporter.LookupTable.Table.{LagIsZero, Prediction, TooFewPoints}

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

object ConsumerGroupCollector {
  import Domain._

  sealed trait Message
  sealed trait Collect extends Message
  final case object Collect extends Collect
  sealed trait Stop extends Message
  final case object Stop extends Stop
  final case class StopWithError(throwable: Throwable) extends Message
  final case class NewOffsets(
                               timestamp: Long,
                               groups: List[ConsumerGroup],
                               latestOffsets: PartitionOffsets,
                               lastGroupOffsets: GroupOffsets
                             ) extends Message

  final case class CollectorConfig(
                                    pollInterval: FiniteDuration,
                                    lookupTableSize: Int,
                                    cluster: KafkaCluster,
                                    clock: Clock = Clock.systemUTC()
                                  )

  final case class CollectorState(
                                   topicPartitionTables: Domain.TopicPartitionTable,
                                   scheduledCollect: Cancellable = Cancellable.alreadyCancelled
                                 )

  def init(config: CollectorConfig,
           clientCreator: KafkaCluster => KafkaClientContract,
           reporter: ActorRef[MetricsSink.Message]): Behavior[Message] = Behaviors.supervise[Message] {
    Behaviors.setup { context =>
      context.log.info("Spawned ConsumerGroupCollector for cluster: {}", config.cluster.name)

      context.self ! Collect

      val collectorState = CollectorState(Domain.TopicPartitionTable(config.lookupTableSize))
      collector(config, clientCreator(config.cluster), reporter, collectorState)
    }
  }.onFailure(SupervisorStrategy.restartWithBackoff(1 seconds, 10 seconds, 0.2))

  def collector(config: CollectorConfig,
                client: KafkaClientContract,
                reporter: ActorRef[MetricsSink.Message],
                state: CollectorState): Behavior[Message] = Behaviors.receive {

    case (context, _: Collect) =>
      implicit val ec: ExecutionContextExecutor = context.executionContext

      def getLatestAndGroupOffsets(groups: List[ConsumerGroup]): Future[NewOffsets] = {
        val now = config.clock.instant().toEpochMilli
        val groupOffsetsFuture = client.getGroupOffsets(now, groups)
        val latestOffsetsTry = client.getLatestOffsets(now, groups)

        for {
          groupOffsets <- groupOffsetsFuture
          Success(latestOffsets) <- Future.successful(latestOffsetsTry)
        } yield NewOffsets(now, groups, latestOffsets, groupOffsets)
      }

      context.log.debug("Collecting offsets")

      val f = for {
        groups <- client.getGroups()
        newOffsets <- getLatestAndGroupOffsets(groups)
      } yield newOffsets

      f.onComplete {
        case Success(newOffsets) =>
          context.self ! newOffsets
        case Failure(t) =>
          context.self ! StopWithError(t)
      }(ec)

      Behaviors.same
    case (context, newOffsets: NewOffsets) =>
      val groupOffsets = defaultMissingPartitions(newOffsets)

      context.log.debug("Update lookup tables")
      addOffsetsToLookupTable(state, newOffsets)

      context.log.debug("Reporting offsets")
      reportLatestOffsetMetrics(config, reporter, state.topicPartitionTables)
      reportConsumerGroupMetrics(config, reporter, groupOffsets, state.topicPartitionTables)

      context.log.debug("Polling in {}", config.pollInterval)
      val newState = state.copy(
        scheduledCollect = context.scheduleOnce(config.pollInterval, context.self, Collect)
      )

      collector(config, client, reporter, newState)
    case (context, _: Stop) =>
      state.scheduledCollect.cancel()
      Behaviors.stopped {
        Behaviors.receiveSignal {
          case (_, PostStop) =>
            client.close()
            context.log.info("Gracefully stopped polling and Kafka client for cluster: {}", config.cluster.name)
            Behaviors.same
        }
      }
    case (_, StopWithError(t)) =>
      state.scheduledCollect.cancel()
      client.close()
      throw new Exception("A failure occurred while retrieving offsets.  Shutting down.", t)
  }

  /**
    * Add Point's to lookup table
    */
  private def addOffsetsToLookupTable(state: CollectorState, newOffsets: NewOffsets): Unit = {
    for {
      (tp, point) <- newOffsets.latestOffsets
    } state.topicPartitionTables(tp).addPoint(point)
  }

  private final case class GroupPartitionLag(gtp: GroupTopicPartition, offsetLag: Long, timeLag: Double)

  private def reportConsumerGroupMetrics(
                                          config: CollectorConfig,
                                          reporter: ActorRef[MetricsSink.Message],
                                          newOffsets: NewOffsets,
                                          tables: TopicPartitionTable
                                        ): Unit = {
    val groupLag: immutable.Iterable[GroupPartitionLag] = for {
      (gtp, groupPoint) <- newOffsets.lastGroupOffsets
      member <- gtp.group.members.find(_.partitions.contains(gtp.topicPartition))
      mostRecentPoint <- tables(gtp.topicPartition).mostRecentPoint().toOption
    } yield {
      val timeLag = tables(gtp.topicPartition).lookup(groupPoint.offset) match {
        case Prediction(pxTime) => (groupPoint.time.toDouble - pxTime) / 1000
        case LagIsZero => 0d
        case TooFewPoints => Double.NaN
      }

      val offsetLag = mostRecentPoint.offset - groupPoint.offset

      reporter ! Metrics.LastGroupOffsetMetric(config.cluster.name, gtp, member, groupPoint.offset)
      reporter ! Metrics.OffsetLagMetric(config.cluster.name, gtp, member, offsetLag)
      reporter ! Metrics.TimeLagMetric(config.cluster.name, gtp, member, timeLag)

      GroupPartitionLag(gtp, offsetLag, timeLag)
    }

    for {
      (group, values) <- groupLag.groupBy(_.gtp.group)
    } {
      val maxOffsetLag = values.maxBy(_.offsetLag)
      val maxTimeLag = values.maxBy(_.timeLag)

      reporter ! Metrics.MaxGroupOffsetLagMetric(config.cluster.name, group, maxOffsetLag.offsetLag)
      reporter ! Metrics.MaxGroupTimeLagMetric(config.cluster.name, group, maxTimeLag.timeLag)
    }
  }

  private def reportLatestOffsetMetrics(
                                         config: CollectorConfig,
                                         reporter: ActorRef[MetricsSink.Message],
                                         tables: TopicPartitionTable
                                       ): Unit = {
    for {
      (tp, table: LookupTable.Table) <- tables.all
      point <- table.mostRecentPoint()
    } reporter ! Metrics.LatestOffsetMetric(config.cluster.name, tp, point.offset)
  }

  private def defaultMissingPartitions(newOffsets: NewOffsets): NewOffsets = {
    val lastGroupOffsetsWithDefaults = newOffsets.groups.flatMap { group =>
      group.members.flatMap(_.partitions).map { tp =>
        val gtp = Domain.GroupTopicPartition(group, tp)
        // get the offset for this partition if provided or return 0
        val measurement = newOffsets.lastGroupOffsets.getOrElse(gtp, LookupTable.Point(0, newOffsets.timestamp))
        gtp -> measurement
      }
    }.toMap

    newOffsets.copy(lastGroupOffsets = lastGroupOffsetsWithDefaults)
  }
}
