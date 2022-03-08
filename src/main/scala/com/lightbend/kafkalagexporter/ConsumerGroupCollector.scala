/*
 * Copyright (C) 2019-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import java.time.Clock
import com.redis._

import akka.actor.Cancellable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import com.lightbend.kafkalagexporter.ConsumerGroupCollector.OffsetsSnapshot.MetricKeys
import com.lightbend.kafkalagexporter.KafkaClient.KafkaClientContract
import com.lightbend.kafkalagexporter.LookupTable.Table.{Inserted, LagIsZero, NonMonotonic, OutOfOrder, Prediction, TooFewPoints, UpdatedRetention, UpdatedSameOffset}

import org.slf4j.Logger

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
  final case class MetaData(pollTime: Long) extends Message
  object OffsetsSnapshot {
    final case class MetricKeys(tps: List[TopicPartition] = Nil,
                                groups: List[String] = Nil,
                                gtps: List[GroupTopicPartition] = Nil)
  }

  final case class OffsetsSnapshot(timestamp: Long,
                                   groups: List[String],
                                   earliestOffsets: PartitionOffsets,
                                   latestOffsets: PartitionOffsets,
                                   lastGroupOffsets: GroupOffsets) extends Message {
    import OffsetsSnapshot._

    private val TpoFormat = "  %-64s%-11s%s"
    private val GtpFormat = "  %-64s%-64s%-11s%s"

    val metricKeys: MetricKeys = MetricKeys(latestOffsets.keys.toList, groups, lastGroupOffsets.keys.toList)

    def diff(other: OffsetsSnapshot): MetricKeys = {
      val evictedTps = metricKeys.tps.diff(other.metricKeys.tps)
      val evictedGroups = metricKeys.groups.diff(other.metricKeys.groups)
      val evictedGtps = metricKeys.gtps.diff(other.metricKeys.gtps)
      MetricKeys(evictedTps, evictedGroups, evictedGtps)
    }

    override def toString: String = {
      val earliestOffsetHeader = TpoFormat.format("Topic", "Partition", "Earliest")
      val earliestOffsetsStr = earliestOffsets.map {
        case (TopicPartition(t, p), Point(offset, _)) => TpoFormat.format(t,p,offset)
      }
      val latestOffsetHeader = TpoFormat.format("Topic", "Partition", "Offset")
      val latestOffsetsStr = latestOffsets.map {
        case (TopicPartition(t, p), Point(offset, _)) => TpoFormat.format(t,p,offset)
      }
      val lastGroupOffsetHeader = GtpFormat.format("Group", "Topic", "Partition", "Offset")
      val lastGroupOffsetsStr = lastGroupOffsets.map {
        case (GroupTopicPartition(id, _, _, _, t, p), Some(Point(offset, _))) => GtpFormat.format(id, t, p, offset)
        case (GroupTopicPartition(id, _, _, _, t, p), None) => GtpFormat.format(id, t, p, "-")
      }

      s"""
         |Timestamp: $timestamp
         |Groups: ${groups.mkString(",")}
         |Earliest Offsets:
         |$earliestOffsetHeader
         |${earliestOffsetsStr.mkString("\n")}
         |Latest Offsets:
         |$latestOffsetHeader
         |${latestOffsetsStr.mkString("\n")}
         |Last Group Offsets:
         |$lastGroupOffsetHeader
         |${lastGroupOffsetsStr.mkString("\n")}
       """.stripMargin
    }
  }

  final case class CollectorConfig(pollInterval: FiniteDuration,
                                   lookupTableSize: Int,
                                   redis: RedisConfig,
                                   cluster: KafkaCluster,
                                   clock: Clock = Clock.systemUTC())

  final case class CollectorState(lastSnapshot: Option[OffsetsSnapshot] = None,
                                  topicPartitionTables: TopicPartitionTable,
                                  scheduledCollect: Cancellable = Cancellable.alreadyCancelled)

  private final case class GroupPartitionLag(gtp: GroupTopicPartition,
                                             offsetLag: Double,
                                             timeLag: Double)

  def init(config: CollectorConfig,
           clientCreator: KafkaCluster => KafkaClientContract,
           reporters: List[ActorRef[MetricsSink.Message]]): Behavior[Message] = Behaviors.supervise[Message] {
    Behaviors.setup { context =>
      context.log.info("Spawned ConsumerGroupCollector for cluster: {}", config.cluster.name)

      context.self ! Collect

      var redisClient: Option[RedisClient] = None
      if (config.redis.enabled) {
        context.log.info("Openning a connection to Redis")
        redisClient = Some(new RedisClient(database = config.redis.database, host = config.redis.host, port = config.redis.port, timeout = config.redis.timeout))
      }
      val collectorState = CollectorState(topicPartitionTables = TopicPartitionTable(limit = config.lookupTableSize, redisConfig = config.redis))
      collector(config, clientCreator(config.cluster), reporters, collectorState, redisClient)
    }
  }.onFailure(SupervisorStrategy.restartWithBackoff(1 seconds, 10 seconds, 0.2))

  def collector(config: CollectorConfig,
                client: KafkaClientContract,
                reporters: List[ActorRef[MetricsSink.Message]],
                state: CollectorState,
                redisClient: Option[RedisClient] = None): Behavior[Message] = (new CollectorBehavior).collector(config, client, reporters, state, redisClient)

  // TODO: Ideally this wouldn't be in a class, like the other behaviors, but at this time there's no other way to
  // TODO: assert state transition changes. See `ConsumerGroupCollectorSpec` which uses mockito to assert the state
  // TODO: transition change
  class CollectorBehavior {
    def collector(config: CollectorConfig,
                  client: KafkaClientContract,
                  reporters: List[ActorRef[MetricsSink.Message]],
                  state: CollectorState,
                  redisClient: Option[RedisClient] = None): Behavior[Message] = Behaviors.receive {
      case (context, _: Collect) =>
        implicit val ec: ExecutionContextExecutor = context.executionContext

        def getOffsetSnapshot(groups: List[String], groupTopicPartitions: List[GroupTopicPartition]): Future[OffsetsSnapshot] = {
          val now = config.clock.instant().toEpochMilli
          val distinctPartitions = groupTopicPartitions.map(_.tp).toSet

          val groupOffsetsFuture = client.getGroupOffsets(now, groups, groupTopicPartitions)
          val earliestOffsetsTry = client.getEarliestOffsets(now, distinctPartitions)
          val latestOffsetsTry = client.getLatestOffsets(now, distinctPartitions)

          for {
            groupOffsets <- groupOffsetsFuture
            Success(earliestOffsets) <- Future.successful(earliestOffsetsTry)
            Success(latestOffsets) <- Future.successful(latestOffsetsTry)
          } yield OffsetsSnapshot(now, groups, earliestOffsets, latestOffsets, groupOffsets)
        }

        context.log.info("Collecting offsets")
        val startPollingTime = config.clock.instant().toEpochMilli
        val f = for {
          (groups, groupTopicPartitions) <- client.getGroups()
          offsetSnapshot <- getOffsetSnapshot(groups, groupTopicPartitions)
        } yield offsetSnapshot

        f.onComplete {
          case Success(newOffsets) =>
            val pollTimeMs = config.clock.instant().toEpochMilli - startPollingTime
            context.self ! newOffsets
            context.self ! MetaData(pollTimeMs)
          case Failure(t) =>
            context.self ! StopWithError(t)
        }(ec)

        Behaviors.same
      case (context, snapshot: OffsetsSnapshot) =>
        context.log.debug("Received Offsets Snapshot:\n{}", snapshot)

        val evictedKeys = state.lastSnapshot.map(_.diff(snapshot)).getOrElse(MetricKeys())

        context.log.info("Updating lookup tables")
        refreshLookupTable(context.log, state.topicPartitionTables, snapshot, evictedKeys.tps, redisClient)

        reporters.foreach { reporter =>
          context.log.info("Reporting offsets")
          reportEarliestOffsetMetrics(config, reporter, snapshot)
          reportLatestOffsetMetrics(config, reporter, snapshot)
          reportConsumerGroupMetrics(context.log, config, reporter, snapshot, state.topicPartitionTables, redisClient)

          context.log.info("Clearing evicted metrics")
          evictMetricsFromReporter(config, reporter, evictedKeys)
        }

        context.log.info("Polling in {}", config.pollInterval)
        val newState = state.copy(
          lastSnapshot = Some(snapshot),
          scheduledCollect = context.scheduleOnce(config.pollInterval, context.self, Collect)
        )

        collector(config, client, reporters, newState, redisClient)

      case (context, metaData: MetaData) =>
        context.log.debug("Received Meta data:\n{}", metaData)
        reporters.foreach{reporter => reportPollTimeMetrics(config, reporter, metaData)}
        Behaviors.same

      case (context, _: Stop) =>
        state.scheduledCollect.cancel()
        Behaviors.stopped { () =>
          client.close()
          redisClient match {
            case Some(client) => client.close()
            case None =>
          }
          evictAllClusterMetrics(context.log, config, reporters, state)
          context.log.info("Gracefully stopped polling and Kafka client for cluster: {}", config.cluster.name)
        }
      case (context, StopWithError(t)) =>
        state.scheduledCollect.cancel()
        client.close()
        redisClient match {
          case Some(client) => client.close()
          case None =>
        }
        evictAllClusterMetrics(context.log, config, reporters, state)
        throw new Exception("A failure occurred while retrieving offsets.  Shutting down.", t)
    }

    /**
      * Evict all metrics from reports before shutdown
      */
    private def evictAllClusterMetrics(log: Logger,
                                       config: CollectorConfig,
                                       reporters: List[ActorRef[MetricsSink.Message]],
                                       state: CollectorState) = {
      log.info("Clearing all metrics before shutdown")
      val metricKeys = state.lastSnapshot.map(_.metricKeys).getOrElse(MetricKeys())
      reporters.foreach(reporter => evictMetricsFromReporter(config, reporter, metricKeys))
    }

    /**
      * Refresh Lookup table.  Remove topic partitions that are no longer relevant and update tables with new Point's.
      */
    private def refreshLookupTable(log: Logger,
                                   topicPartitionTables: TopicPartitionTable,
                                   snapshot: OffsetsSnapshot,
                                   evictedTps: List[TopicPartition],
                                   redisClient: Option[RedisClient]): Unit = {
      topicPartitionTables.clear(evictedTps)
      for((tp, point) <- snapshot.latestOffsets) {
        topicPartitionTables(tp) match {
          case Left(memory) =>
            log.info("  Point ({}, {}) was added in the in-memory lookup table ({}, {})", point.offset.toString(), point.time.toString(), tp.topic, tp.partition.toString())
            memory.addPoint(point)
          case Right(redis) =>
            redisClient match {
              case Some(client) =>
                redis.addPoint(point, client) match {
                  case Inserted => log.info("  Point ({}, {}) was added in the redis lookup table ({}, {})", point.offset.toString(), point.time.toString(), tp.topic, tp.partition.toString())
                  case NonMonotonic => log.info("  Point ({}, {}) was not added in the redis lookup table ({}, {}) because it was not part of a monotonically increasing set", point.offset.toString(), point.time.toString(), tp.topic, tp.partition.toString())
                  case OutOfOrder => log.info("  Point ({}, {}) was not added in the redis lookup table ({}, {}) because the time is older than the previous point", point.offset.toString(), point.time.toString(), tp.topic, tp.partition.toString())
                  case UpdatedRetention => log.info("  Point ({}, {}) was updated in the redis lookup table ({}, {}) because the last insert was too recent", point.offset.toString(), point.time.toString(), tp.topic, tp.partition.toString())
                  case UpdatedSameOffset => log.info("  Point ({}, {}) was updated in the redis lookup table ({}, {}) because the offset was the same", point.offset.toString(), point.time.toString(), tp.topic, tp.partition.toString())
                }
              case None => log.error("  Point ({}, {}) was not added in the lookup table as the lookup table ({}, {}) is not in-memory nor in redis", point.offset.toString(), point.time.toString(), tp.topic, tp.partition.toString())
            }
        }
      }
    }

    private def reportConsumerGroupMetrics(log: Logger,
                                           config: CollectorConfig,
                                           reporter: ActorRef[MetricsSink.Message],
                                           offsetsSnapshot: OffsetsSnapshot,
                                           tables: TopicPartitionTable,
                                           redisClient: Option[RedisClient]): Unit = {
      val groupLag: immutable.Iterable[GroupPartitionLag] = for {
        (gtp, groupPoint) <- offsetsSnapshot.lastGroupOffsets
        (_, mrp) <- offsetsSnapshot.latestOffsets.filterKeys(_ == gtp.tp)
      } yield {
        val (groupOffset, offsetLag, timeLag) = groupPoint match {
          case Some(point) =>
            val groupOffset = point.offset.toDouble
            val timeLagResult = tables(gtp.tp) match {
              case Left(memory) =>
                log.info("Find time lag for offset {} using the in-memory lookup table ({}, {})", point.offset.toString(), gtp.tp.topic, gtp.tp.partition.toString())
                memory.lookup(point.offset)
              case Right(redis) => {
                redisClient match {
                  case Some(client) =>
                    log.info("Find time lag for offset {} using the redis lookup table ({}, {})", point.offset.toString(), gtp.tp.topic, gtp.tp.partition.toString())
                    redis.lookup(point.offset, client)
                  case None => log.error("Unable to find the time lag for offset {} as the lookup table ({}, {}) is not in-memory nor in redis", point.offset.toString(), gtp.tp.topic, gtp.tp.partition.toString())
                }
              }
            }
            val timeLag = timeLagResult match {
              case Prediction(pxTime) => (point.time.toDouble - pxTime) / 1000
              case LagIsZero          => 0d
              case TooFewPoints       => Double.NaN
            }
            val offsetLagCalc = mrp.offset - point.offset
            val offsetLag = if (offsetLagCalc < 0) 0d else offsetLagCalc
            (groupOffset, offsetLag, timeLag)
          case None => (Double.NaN, Double.NaN, Double.NaN)
        }

        reporter ! Metrics.GroupPartitionValueMessage(Metrics.LastGroupOffsetMetric, config.cluster.name, gtp, groupOffset)
        reporter ! Metrics.GroupPartitionValueMessage(Metrics.OffsetLagMetric, config.cluster.name, gtp, offsetLag)
        reporter ! Metrics.GroupPartitionValueMessage(Metrics.TimeLagMetric, config.cluster.name, gtp, timeLag)

        GroupPartitionLag(gtp, offsetLag, timeLag)
      }

      for((group, groupValues) <- groupLag.groupBy(_.gtp.id)) {
        val maxOffsetLag = groupValues.maxBy(_.offsetLag)
        val maxTimeLag = groupValues.maxBy(_.timeLag)

        reporter ! Metrics.GroupValueMessage(Metrics.MaxGroupOffsetLagMetric, config.cluster.name, group, maxOffsetLag.offsetLag)
        reporter ! Metrics.GroupValueMessage(Metrics.MaxGroupTimeLagMetric, config.cluster.name, group, maxTimeLag.timeLag)

        val sumOffsetLag = groupValues.map(_.offsetLag).filter(offsetLag => !offsetLag.isNaN).sum
        reporter ! Metrics.GroupValueMessage(Metrics.SumGroupOffsetLagMetric, config.cluster.name, group, sumOffsetLag)

        for((topic, topicValues) <- groupValues.groupBy(_.gtp.topic)) {
          val topicOffsetLag = topicValues.map(_.offsetLag).filter(offsetLag => !offsetLag.isNaN).sum

          reporter ! Metrics.GroupTopicValueMessage(Metrics.SumGroupTopicOffsetLagMetric, config.cluster.name, group, topic, topicOffsetLag)
        }
      }
    }

    private def reportEarliestOffsetMetrics(config: CollectorConfig,
                                            reporter: ActorRef[MetricsSink.Message],
                                            offsetsSnapshot: OffsetsSnapshot): Unit = {
      for {(tp, topicPoint) <- offsetsSnapshot.earliestOffsets} yield {
        reporter ! Metrics.TopicPartitionValueMessage(Metrics.EarliestOffsetMetric, config.cluster.name, tp, topicPoint.offset)
      }
    }

    private def reportLatestOffsetMetrics(config: CollectorConfig,
                                          reporter: ActorRef[MetricsSink.Message],
                                          offsetsSnapshot: OffsetsSnapshot): Unit = {
      for {(tp, topicPoint) <- offsetsSnapshot.latestOffsets} yield {
        reporter ! Metrics.TopicPartitionValueMessage(Metrics.LatestOffsetMetric, config.cluster.name, tp, topicPoint.offset)
      }
    }

    private def evictMetricsFromReporter(config: CollectorConfig,
                                         reporter: ActorRef[MetricsSink.Message],
                                         metricKeys: MetricKeys): Unit = {
      metricKeys.tps.foreach { tp =>
        reporter ! Metrics.TopicPartitionRemoveMetricMessage(Metrics.LatestOffsetMetric, config.cluster.name, tp)
        reporter ! Metrics.TopicPartitionRemoveMetricMessage(Metrics.EarliestOffsetMetric, config.cluster.name, tp)
      }
      metricKeys.groups.foreach { group =>
        reporter ! Metrics.GroupRemoveMetricMessage(Metrics.MaxGroupOffsetLagMetric, config.cluster.name, group)
        reporter ! Metrics.GroupRemoveMetricMessage(Metrics.MaxGroupTimeLagMetric, config.cluster.name, group)
        reporter ! Metrics.GroupRemoveMetricMessage(Metrics.SumGroupOffsetLagMetric, config.cluster.name, group)
      }
      metricKeys.gtps.foreach { gtp =>
        reporter ! Metrics.GroupPartitionRemoveMetricMessage(Metrics.LastGroupOffsetMetric, config.cluster.name, gtp)
        reporter ! Metrics.GroupPartitionRemoveMetricMessage(Metrics.OffsetLagMetric, config.cluster.name, gtp)
        reporter ! Metrics.GroupPartitionRemoveMetricMessage(Metrics.TimeLagMetric, config.cluster.name, gtp)
      }

      for {
        (group, gtps) <- metricKeys.gtps.groupBy(_.id)
        topic <- gtps.map(_.topic).distinct
      } reporter ! Metrics.GroupTopicRemoveMetricMessage(Metrics.SumGroupTopicOffsetLagMetric, config.cluster.name, group, topic)
    }
  }

  private def reportPollTimeMetrics(config: CollectorConfig,
                                    reporter: ActorRef[MetricsSink.Message],
                                    metaData: MetaData): Unit = {
    reporter ! Metrics.ClusterValueMessage(Metrics.PollTimeMetric, config.cluster.name, metaData.pollTime)
  }
}
