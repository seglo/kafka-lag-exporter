package com.lightbend.kafka.sparkeventexporter.internal
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, PostStop}
import com.lightbend.kafka.kafkametricstools.Domain.{Measurements, PartitionOffsets, TopicPartition}
import com.lightbend.kafka.kafkametricstools.KafkaClient.KafkaClientContract
import com.lightbend.kafka.kafkametricstools.{KafkaCluster, MetricsSink, PrometheusEndpointSink}
import com.lightbend.kafka.sparkeventexporter.internal.Domain.{Query, SourceMetrics}

import scala.concurrent.ExecutionContextExecutor
import scala.util.{Failure, Success}

object MetricCollector {
  sealed trait Message
  sealed trait Stop extends Message
  final case object Stop extends Stop

  final case class QueryResults(query: Query) extends Message

  final case class NewOffsets(
                               sparkAppId: String,
                               sourceMetrics: List[SourceMetrics],
                               latestOffsets: PartitionOffsets,
                               lastOffsets: PartitionOffsets,
                             ) extends Message


  final case class CollectorState(
                                   name: String,
                                   cluster: KafkaCluster,
                                   latestOffsets: PartitionOffsets = PartitionOffsets(),
                                   lastOffsets: PartitionOffsets = PartitionOffsets(),
                                 )

  def init(state: CollectorState,
           clientCreator: () => KafkaClientContract,
           reporter: ActorRef[MetricsSink.Message]): Behavior[Message] = Behaviors.setup { _ =>
    collector(clientCreator(), reporter, state)
  }

  def collector(
                 client: KafkaClientContract,
                 reporter: ActorRef[MetricsSink.Message],
                 state: CollectorState
               ): Behavior[Message] = Behaviors.receive {
    case (context, queryResults: QueryResults) =>
      implicit val ec: ExecutionContextExecutor = context.executionContext

      val sourceMetrics = queryResults.query.sourceMetrics
      val sparkAppId = queryResults.query.sparkAppId

      val topicPartitions: Set[TopicPartition] = for {
        source: Domain.SourceMetrics <- sourceMetrics.toSet
        (topicPartition, _) <- source.endOffsets
      } yield topicPartition

      val lastOffsets = sourceMetrics.foldLeft(PartitionOffsets()) { (acc, source) =>
        acc ++ source.endOffsets
      }

      val f = for {
        latestOffsets <- client.getLatestOffsets(queryResults.query.timestamp, topicPartitions)
      } yield NewOffsets(sparkAppId, sourceMetrics, latestOffsets, lastOffsets)

      f.onComplete {
        case Success(newState) =>
          context.self ! newState
        case Failure(ex) =>
          context.log.error(ex, "An error occurred while retrieving offsets")
          context.self ! Stop
      }(ec)

      Behaviors.same
    case (_, newOffsets: NewOffsets) =>
      val mergedState = mergeState(state, newOffsets)

      reportThroughputMetrics(newOffsets.sparkAppId, newOffsets.sourceMetrics, state, reporter)
      //reportLatestOffsetMetrics(newOffsets.sparkAppId, mergedState, reporter)
      reportConsumerMetrics(newOffsets.sparkAppId, mergedState, reporter)

      collector(client, reporter, mergedState)
    case (context, _: Stop) =>
      Behaviors.stopped {
        Behaviors.receiveSignal {
          case (_, PostStop) =>
            client.close()
            context.log.info("Gracefully stopped polling and Kafka client for cluster: {}", state.cluster.name)
            Behaviors.same
        }
      }
  }

  private def reportThroughputMetrics(
                                       sparkAppId: String,
                                       sourceMetrics: List[SourceMetrics],
                                       state: CollectorState,
                                       reporter: ActorRef[MetricsSink.Message]): Unit = {
    for(sourceMetric <- sourceMetrics) {
      /**
        * A Spark Query subscription could contain more than 1 topic, but throughput is only available per Source
        */
      val sourceTopics = sourceMetric.endOffsets.keys.map(_.topic).toSet.mkString(",")
      reporter ! Metrics.InputRecordsPerSecondMetric(state.cluster.name, sparkAppId, state.name, sourceTopics, sourceMetric.inputRecordsPerSecond)
      reporter ! Metrics.OutputRecordsPerSecondMetric(state.cluster.name, sparkAppId, state.name, sourceTopics, sourceMetric.outputRecordsPerSecond)
    }
  }

  private def reportConsumerMetrics(
                                      sparkAppId: String,
                                      state: CollectorState,
                                      reporter: ActorRef[MetricsSink.Message]
                                   ): Unit = {
    for {
      (tp, measurement: Measurements.Double) <- state.lastOffsets
      latestOffset <- state.latestOffsets.get(tp)
    } {
      val offsetLag = measurement.offsetLag(latestOffset.offset)
      val timeLag = measurement.timeLag(latestOffset.offset)

      reporter ! Metrics.LastOffsetMetric(state.cluster.name, sparkAppId, state.name, tp, measurement.b.offset)
      reporter ! Metrics.OffsetLagMetric(state.cluster.name, sparkAppId, state.name, tp, offsetLag)
      reporter ! Metrics.TimeLagMetric(state.cluster.name, sparkAppId, state.name, tp, timeLag)
    }
  }

  private def reportLatestOffsetMetrics(
                                         sparkAppId: String,
                                         state: CollectorState,
                                         reporter: ActorRef[MetricsSink.Message]
                                       ): Unit = {
    for ((tp, measurement: Measurements.Single) <- state.latestOffsets)
      reporter ! Metrics.LatestOffsetMetric(state.cluster.name, sparkAppId, state.name, tp, measurement.offset)
  }

  private def mergeState(state: CollectorState, newOffsets: NewOffsets): CollectorState = {
    val mergedOffsets: PartitionOffsets = for {
      (topicPartition, newMeasurement: Measurements.Single) <- newOffsets.lastOffsets
    } yield {
      topicPartition -> state.lastOffsets
        .get(topicPartition)
        .map(measurement => measurement.addMeasurement(newMeasurement))
        .getOrElse(newMeasurement)
    }
    state.copy(latestOffsets = newOffsets.latestOffsets, lastOffsets = mergedOffsets)
  }
}
