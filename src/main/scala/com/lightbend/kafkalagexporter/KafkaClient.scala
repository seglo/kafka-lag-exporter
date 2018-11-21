package com.lightbend.kafkalagexporter

import java.time.Instant
import java.util
import java.util.Properties

import com.lightbend.kafkalagexporter.Offsets.ConsumerGroupId
import kafka.admin.ConsumerGroupCommand.LogOffsetResult
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.admin.{AdminClient, ConsumerGroupDescription}
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer, OffsetAndTimestamp}
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.{KafkaFuture, TopicPartition}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future, Promise}

object KafkaClient {
  def apply(bootstrapBrokers: String)(implicit ec: ExecutionContext): KafkaClientContract = KafkaClientDirect(bootstrapBrokers)(ec)
}

trait KafkaClientContract {
  def getGroupIds(): Future[List[ConsumerGroupId]]
  def getLatestOffsets(groupIds: List[ConsumerGroupId]): Future[Map[Offsets.TopicPartition, Long]]
  def getGroupOffsets(groupIds: List[ConsumerGroupId]): Future[Map[ConsumerGroupId, Map[Offsets.TopicPartition, Long]]]
  def close(): Unit
}

object KafkaClientDirect {
  def apply(bootstrapBrokers: String)(implicit ec: ExecutionContext): KafkaClientContract = new KafkaClientDirect(bootstrapBrokers)(ec)

  private def kafkaFuture[T](kafkaFuture: KafkaFuture[T])(implicit ec: ExecutionContext): Future[T] = {
    val p = Promise[T]()
    Future {
      try {
        p.success(kafkaFuture.get())
      } catch {
        case ex: Throwable => p.failure(ex)
      }
    }(ec)
    p.future
  }

  private def createAdminClient(brokers: String): AdminClient = {
    val props = new Properties()
    props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, brokers)
    AdminClient.create(props)
  }

  private def createConsumerClient(brokers: String, groupId: ConsumerGroupId): KafkaConsumer[String, String] = {
    val properties = new Properties()
    val deserializer = (new StringDeserializer).getClass.getName
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
    properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
    properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
    properties.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "30000")
    properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, deserializer)
    properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, deserializer)
    new KafkaConsumer(properties)
  }
}

class KafkaClientDirect private(bootstrapBrokers: String, consumerGroupId: Option[ConsumerGroupId] = None)
                               (implicit ec: ExecutionContext) extends KafkaClientContract {
  import KafkaClientDirect._

  private val groupId = consumerGroupId.getOrElse("kafkalagexporter")

  private val adminClient = createAdminClient(bootstrapBrokers)
  private val consumer = createConsumerClient(bootstrapBrokers, groupId)

  def printSystemInfo() = {
    println("Processors: " + Runtime.getRuntime().availableProcessors())
    println("Thread active: " + Thread.activeCount)
    println("Current Thread: " + Thread.currentThread().getId())
  }

  def getGroupIds(): Future[List[ConsumerGroupId]] = {
    for {
      groups <- kafkaFuture(adminClient.listConsumerGroups().all())
    } yield groups.asScala.map(_.groupId()).toList
  }

  def getLatestOffsets(groupIds: List[ConsumerGroupId]): Future[Map[Offsets.TopicPartition, Long]] = {
    for {
      groupDescriptions <- kafkaFuture(adminClient.describeConsumerGroups(groupIds.asJavaCollection).all())
      groupPartitions = dedupeGroupPartitions(groupDescriptions)
      //groupPartitionsLatestOffsets <- Future(getOffsetsForTimes(groupPartitions, now))
      groupPartitionsLatestOffsets <- Future(getLogEndOffsets(groupPartitions))
    } yield {
//      println(s"groupDescriptions: $groupDescriptions")
//      println(s"groupPartitions: $groupPartitions")
//      println(s"groupPartitionsLatestOffsets: $groupPartitionsLatestOffsets")
      groupPartitionsLatestOffsets.map {
        case (tp, offset) => Offsets.TopicPartition(tp.topic, tp.partition) -> offset
      }
    }
  }

  private def dedupeGroupPartitions(groupDescriptions: util.Map[ConsumerGroupId, ConsumerGroupDescription]): List[TopicPartition] = {
    groupDescriptions.asScala.values.flatMap { memberDescription =>
      memberDescription.members().asScala
        .flatMap(partitions => partitions.assignment().topicPartitions().asScala)
    }.toSet.toList
  }

  private def getLogEndOffsets(topicPartitions: List[TopicPartition]): Map[Offsets.TopicPartition, Long] = {
    val offsets = consumer.endOffsets(topicPartitions.asJava)
    topicPartitions.map { tp =>
      val logEndOffset = offsets.get(tp)
      Offsets.TopicPartition(tp.topic, tp.partition) -> logEndOffset.toLong
    }.toMap
  }

  private def getOffsetsForTimes(topicPartitions: List[TopicPartition], timestamp: Long): Map[Offsets.TopicPartition, Long] = {
    val timestampsToSearch = topicPartitions.map(tp => tp -> long2Long(timestamp)).toMap
    val offsets: util.Map[TopicPartition, OffsetAndTimestamp] = consumer.offsetsForTimes(timestampsToSearch.asJava)
    topicPartitions.flatMap { tp =>
      Option(offsets.get(tp)).map { logEndOffset =>
        Offsets.TopicPartition(tp.topic, tp.partition) -> logEndOffset.offset()
      }.toList
    }.toMap
  }

  def getGroupOffsets(groupIds: List[ConsumerGroupId]): Future[Map[ConsumerGroupId, Map[Offsets.TopicPartition, Long]]] = {
    Future.sequence {
      groupIds.map { groupId =>
        kafkaFuture(adminClient.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata())
          .map { offsets =>
//            println(s"offsets: $offsets")
            groupId -> offsets.asScala.map {
              case (tp, offsets) => Offsets.TopicPartition(tp.topic, tp.partition) -> offsets.offset()
            }.toMap
          }
      }
    }.map(_.toMap)
  }

  def close(): Unit = {
    adminClient.close()
    consumer.close()
  }
}



