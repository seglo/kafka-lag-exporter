package com.lightbend.kafka.kafkalagexporter

import java.time.Clock

import akka.actor.Cancellable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, PostStop, SupervisorStrategy}
import com.lightbend.kafka.kafkametricstools.KafkaClient.KafkaClientContract
import com.lightbend.kafka.kafkametricstools.LookupTable.Point
import com.lightbend.kafka.kafkametricstools.{Domain, LookupTable, MetricsSink}

import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}
import scala.concurrent.duration._

object ConsumerGroupCollector {
  import com.lightbend.kafka.kafkametricstools.Domain._

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
                                    clusterName: String,
                                    clusterBootstrapBrokers: String,
                                    clock: Clock = Clock.systemUTC()
                                  )

  final case class CollectorState(
                                   latestOffsets: Domain.PartitionOffsets = Domain.PartitionOffsets(),
                                   lastGroupOffsets: Domain.GroupOffsets = Domain.GroupOffsets(),
                                   topicPartitionTables: Domain.TopicPartitionTable = Domain.TopicPartitionTable(),
                                   scheduledCollect: Cancellable = Cancellable.alreadyCancelled
                                 )

  def init(config: CollectorConfig,
           clientCreator: String => KafkaClientContract,
           reporter: ActorRef[MetricsSink.Message]): Behavior[Message] = Behaviors.supervise[Message] {
    Behaviors.setup { context =>
      context.log.info(s"Spawned ConsumerGroupCollector for cluster ${config.clusterName}")

      context.self ! Collect

      val collectorState = CollectorState()
      collector(config, clientCreator(config.clusterBootstrapBrokers), reporter, collectorState)
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

      // Add Point's to lookup table
      for {
        (tp, measurement) <- newOffsets.latestOffsets
      } yield {
        val offset = measurement.offset
        val timestamp = measurement.timestamp
        val point = LookupTable.Point(offset, timestamp)
        state.topicPartitionTables(tp).addPoint(point)
      }

      //val mergedLastGroupOffsets = mergeLastGroupOffsets(state.lastGroupOffsets, newOffsetsWithDefaults)

      context.log.debug("Reporting offsets")

      reportLatestOffsetMetrics(config, reporter, state.topicPartitionTables)
      reportConsumerGroupMetrics(config, reporter, groupOffsets, state.topicPartitionTables)

      context.log.debug("Polling in {}", config.pollInterval)
      val scheduledCollect = context.scheduleOnce(config.pollInterval, context.self, Collect)

      val newState = state.copy(
        latestOffsets = groupOffsets.latestOffsets,
        //lastGroupOffsets = mergedLastGroupOffsets,
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
    case (_, StopWithError(t)) =>
      state.scheduledCollect.cancel()
      client.close()
      throw new Exception("A failure occurred while retrieving offsets.  Shutting down.", t)
  }

  private final case class GroupPartitionLag(gtp: GroupTopicPartition, offsetLag: Long, timeLag: Double)

  private def reportConsumerGroupMetrics(
                                          config: CollectorConfig,
                                          reporter: ActorRef[MetricsSink.Message],
                                          newOffsets: NewOffsets,
                                          tables: TopicPartitionTable
                                        ): Unit = {
    val groupLag: immutable.Iterable[GroupPartitionLag] = for {
      (gtp, measurement: Measurements.Measurement) <- newOffsets.lastGroupOffsets
      member <- gtp.group.members.find(_.partitions.contains(gtp.topicPartition))
      latestOffset <- tables(gtp.topicPartition).lastOffset().toOption
      // TODO: Return Option or Either for lookup
      pxTime = tables(gtp.topicPartition).lookup(measurement.offset) if !pxTime.isNaN && !pxTime.isInfinite
    } yield {
      val delta = (newOffsets.timestamp.toDouble - pxTime) / 1000
      val offsetLag = measurement.offsetLag(latestOffset.offset)
      //val timeLag = 0
      //val timeLag = measurement.timeLag(latestOffset.offset)

      reporter ! Metrics.LastGroupOffsetMetric(config.clusterName, gtp, member, measurement.offset)
      reporter ! Metrics.OffsetLagMetric(config.clusterName, gtp, member, offsetLag)
      reporter ! Metrics.TimeLagMetric(config.clusterName, gtp, member, delta)

      GroupPartitionLag(gtp, offsetLag, delta)
    }

    for {
      (group, values) <- groupLag.groupBy(_.gtp.group)
    } {
      val maxOffsetLag = values.maxBy(_.offsetLag)
      val maxTimeLag = values.maxBy(_.timeLag)

      reporter ! Metrics.MaxGroupOffsetLagMetric(config.clusterName, group, maxOffsetLag.offsetLag)
      reporter ! Metrics.MaxGroupTimeLagMetric(config.clusterName, group, maxTimeLag.timeLag)
    }
  }

  private def reportLatestOffsetMetrics(
                                         config: CollectorConfig,
                                         reporter: ActorRef[MetricsSink.Message],
                                         tables: TopicPartitionTable
                                       ): Unit = {
    for {
      (tp, table: LookupTable.Table) <- tables.all
      point <- table.lastOffset()
    } reporter ! Metrics.LatestOffsetMetric(config.clusterName, tp, point.offset)
  }

  private def defaultMissingPartitions(newOffsets: NewOffsets): NewOffsets = {
    val lastGroupOffsetsWithDefaults = newOffsets.groups.flatMap { group =>
      group.members.flatMap(_.partitions).map { tp =>
        val gtp = Domain.GroupTopicPartition(group, tp)
        // get the offset for this partition if provided or return 0
        val measurement = newOffsets.lastGroupOffsets.getOrElse(gtp, Measurements.Single(0, newOffsets.timestamp))
        gtp -> measurement
      }
    }.toMap

    newOffsets.copy(lastGroupOffsets = lastGroupOffsetsWithDefaults)
  }

//  private def mergeLastGroupOffsets(
//                                     lastGroupOffsets: GroupOffsets,
//                                     newOffsets: NewOffsets): GroupOffsets = {
//    for {
//      (groupTopicPartition, newMeasurement: Measurements.Single) <- newOffsets.lastGroupOffsets
//    } yield {
//      groupTopicPartition -> lastGroupOffsets
//        .get(groupTopicPartition)
//        .map(measurement => measurement.addMeasurement(newMeasurement))
//        .getOrElse(newMeasurement)
//    }
//  }
}
