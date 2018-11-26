package com.lightbend.kafkalagexporter

import java.util
import java.util.Properties

import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.admin.{AdminClient, ConsumerGroupDescription}
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer, OffsetAndTimestamp}
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.{KafkaFuture, TopicPartition => KafkaTopicPartition }

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future, Promise}

object KafkaClient {
  def apply(bootstrapBrokers: String)(implicit ec: ExecutionContext): KafkaClientContract = KafkaClientDirect(bootstrapBrokers)(ec)
}

trait KafkaClientContract {
  def getGroups(): Future[List[Offsets.ConsumerGroup]]
  def getLatestOffsets(groups: List[Offsets.ConsumerGroup]): Future[Map[Offsets.TopicPartition, Long]]
  def getGroupOffsets(groups: List[Offsets.ConsumerGroup]): Future[Map[Offsets.ConsumerGroup, Map[Offsets.TopicPartition, Long]]]
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

  private def createConsumerClient(brokers: String, groupId: String): KafkaConsumer[String, String] = {
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

  // extension methods to convert between Offsets.TopicPartition and org.apache.kafka.common.TopicPartition
  private implicit class OffsetsTopicPartitionOps(ktp: KafkaTopicPartition) {
    def asOffsets: Offsets.TopicPartition = Offsets.TopicPartition(ktp.topic(), ktp.partition())
  }

  private implicit class KafkaTopicPartitionOps(tp: Offsets.TopicPartition) {
    def asKafka: KafkaTopicPartition = new KafkaTopicPartition(tp.topic, tp.partition)
  }
}

class KafkaClientDirect private(bootstrapBrokers: String, consumerGroupId: Option[String] = None)
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

  def getGroups(): Future[List[Offsets.ConsumerGroup]] = {
    for {
      groups <- kafkaFuture(adminClient.listConsumerGroups().all())
      groupIds = groups.asScala.map(_.groupId()).toList
      groupDescriptions <- kafkaFuture(adminClient.describeConsumerGroups(groupIds.asJava).all())
    } yield
      groupDescriptions.asScala.map { case (id, desc) => getGroupDescription(id, desc) }.toList
  }

  private def getGroupDescription(groupId: String, groupDescription: ConsumerGroupDescription): Offsets.ConsumerGroup = {
    val members = groupDescription.members().asScala.map { member =>
      val partitions = member
        .assignment()
        .topicPartitions()
        .asScala
        .map(tp => Offsets.TopicPartition(tp.topic(), tp.partition()))
        .toSet
      Offsets.ConsumerGroupMember(member.clientId(), member.consumerId(), member.host(), partitions)
    }.toList
    Offsets.ConsumerGroup(groupId, groupDescription.isSimpleConsumerGroup, groupDescription.state.toString, members)
  }

  def getLatestOffsets(groups: List[Offsets.ConsumerGroup]): Future[Map[Offsets.TopicPartition, Long]] = {
    val partitions = dedupeGroupPartitions(groups)

    for(groupPartitionsLatestOffsets <- Future(getLogEndOffsets(partitions))) yield {
      groupPartitionsLatestOffsets.map {
        case (tp, offset) => tp -> offset
      }
    }
  }

  private def dedupeGroupPartitions(groups: List[Offsets.ConsumerGroup]): Set[Offsets.TopicPartition] = {
    groups.flatMap(_.members.flatMap(_.partitions)).toSet
  }

  private def getLogEndOffsets(topicPartitions: Set[Offsets.TopicPartition]): Map[Offsets.TopicPartition, Long] = {
    val offsets = consumer.endOffsets(topicPartitions.map(_.asKafka).asJava)
    topicPartitions.map(tp => tp -> offsets.get(tp.asKafka).toLong).toMap
  }

  private def getOffsetsForTimes(topicPartitions: Set[Offsets.TopicPartition], timestamp: Long): Map[Offsets.TopicPartition, Long] = {
    val timestampsToSearch = topicPartitions.map(tp => tp.asKafka -> long2Long(timestamp)).toMap

    val offsets: util.Map[KafkaTopicPartition, OffsetAndTimestamp] = consumer.offsetsForTimes(timestampsToSearch.asJava)
    topicPartitions.flatMap { tp =>
      Option(offsets.get(tp.asKafka))
        .map(logEndOffset => tp -> logEndOffset.offset()).toList
    }.toMap
  }

  def getGroupOffsets(groups: List[Offsets.ConsumerGroup]): Future[Map[Offsets.ConsumerGroup, Map[Offsets.TopicPartition, Long]]] = {
    Future.sequence {
      groups.map { group =>
        kafkaFuture(adminClient.listConsumerGroupOffsets(group.id).partitionsToOffsetAndMetadata()).map { offsets =>
          group -> offsets.asScala.map { case (tp, offsets) => tp.asOffsets -> offsets.offset() }.toMap
        }
      }
    }.map(_.toMap)
  }

  def close(): Unit = {
    adminClient.close()
    consumer.close()
  }
}
