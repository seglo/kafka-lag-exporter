package com.lightbend.kafka.kafkametricstools

import java.time.Duration
import java.util.Properties
import java.{lang, util}

import com.lightbend.kafka.kafkametricstools.Domain.Measurements
import com.lightbend.kafka.kafkametricstools.KafkaClient.KafkaClientContract
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.admin.{AdminClient, ConsumerGroupDescription}
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer, OffsetAndMetadata}
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.{KafkaFuture, TopicPartition => KafkaTopicPartition}

import scala.collection.JavaConverters._
import scala.compat.java8.DurationConverters._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try

object KafkaClient {
  def apply(bootstrapBrokers: String, groupId: String, consumerTimeout: FiniteDuration)(implicit ec: ExecutionContext): KafkaClientContract =
    new KafkaClient(bootstrapBrokers, groupId, consumerTimeout)(ec)

  trait KafkaClientContract {
    def getGroups(): Future[List[Domain.ConsumerGroup]]
    def getGroupOffsets(now: Long, groups: List[Domain.ConsumerGroup]): Future[Map[Domain.GroupTopicPartition, Measurements.Measurement]]
    def getLatestOffsets(now: Long, groups: List[Domain.ConsumerGroup]): Try[Map[Domain.TopicPartition, Measurements.Single]]
    def getLatestOffsets(now: Long, topicPartitions: Set[Domain.TopicPartition]): Try[Map[Domain.TopicPartition, Measurements.Single]]
    def close(): Unit
  }

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
    def asDomain: Domain.TopicPartition = Domain.TopicPartition(ktp.topic(), ktp.partition())
  }

  private implicit class KafkaTopicPartitionOps(tp: Domain.TopicPartition) {
    def asKafka: KafkaTopicPartition = new KafkaTopicPartition(tp.topic, tp.partition)
  }
}

class KafkaClient private(bootstrapBrokers: String, groupId: String, consumerTimeout: FiniteDuration)
                         (implicit ec: ExecutionContext) extends KafkaClientContract {
  import KafkaClient._

  private val _consumerTimeout: Duration = consumerTimeout.toJava
  private lazy val adminClient = createAdminClient(bootstrapBrokers)
  private lazy val consumer = createConsumerClient(bootstrapBrokers, groupId)

  def getGroups(): Future[List[Domain.ConsumerGroup]] = {
    for {
      groups <- kafkaFuture(adminClient.listConsumerGroups().all())
      groupIds = groups.asScala.map(_.groupId()).toList
      groupDescriptions <- kafkaFuture(adminClient.describeConsumerGroups(groupIds.asJava).all())
    } yield groupDescriptions.asScala.map { case (id, desc) => createConsumerGroup(id, desc) }.toList
  }

  private def createConsumerGroup(groupId: String, groupDescription: ConsumerGroupDescription): Domain.ConsumerGroup = {
    val members = groupDescription.members().asScala.map { member =>
      val partitions = member
        .assignment()
        .topicPartitions()
        .asScala
        .map(_.asDomain)
        .toSet
      Domain.ConsumerGroupMember(member.clientId(), member.consumerId(), member.host(), partitions)
    }.toList
    Domain.ConsumerGroup(groupId, groupDescription.isSimpleConsumerGroup, groupDescription.state.toString, members)
  }

  def getLatestOffsets(now: Long, groups: List[Domain.ConsumerGroup]): Try[Map[Domain.TopicPartition, Measurements.Single]] = {
    val partitions = dedupeGroupPartitions(groups)
    getLatestOffsets(now, partitions)
  }

  private def dedupeGroupPartitions(groups: List[Domain.ConsumerGroup]): Set[Domain.TopicPartition] = {
    groups.flatMap(_.members.flatMap(_.partitions)).toSet
  }

  /**
    * Get latest offsets for a set of topic partitions.
    */
  def getLatestOffsets(now: Long, topicPartitions: Set[Domain.TopicPartition]): Try[Map[Domain.TopicPartition, Measurements.Single]] = Try {
    val offsets: util.Map[KafkaTopicPartition, lang.Long] = consumer.endOffsets(topicPartitions.map(_.asKafka).asJava, _consumerTimeout)
    topicPartitions.map(tp => tp -> Measurements.Single(offsets.get(tp.asKafka).toLong,now)).toMap
  }

  /**
    * Get last committed Consumer Group offsets for all group topic partitions.  When a topic partition has no matched
    * Consumer Group offset then a default offset of 0 is provided.
    * @param groups A list of Consumer Groups to request offsets for.
    * @return A series of Future's for Consumer Group offsets requests to Kafka.
    */
  def getGroupOffsets(now: Long, groups: List[Domain.ConsumerGroup]): Future[Map[Domain.GroupTopicPartition, Measurements.Measurement]] = {
    def actualGroupOffsets(group: Domain.ConsumerGroup, offsetMap: Map[KafkaTopicPartition, OffsetAndMetadata]): List[(Domain.GroupTopicPartition, Measurements.Single)] = {
      offsetMap.map { case (tp, offsets) =>
        Domain.GroupTopicPartition(group, tp.asDomain) -> Measurements.Single(offsets.offset(), now)
      }.toList

    }

    Future.sequence {
      groups.map { group =>
        kafkaFuture(adminClient.listConsumerGroupOffsets(group.id).partitionsToOffsetAndMetadata())
          .map(offsetMap => actualGroupOffsets(group, offsetMap.asScala.toMap))
      }
    }.map(_.flatten.toMap)
  }

  def close(): Unit = {
    adminClient.close()
    consumer.close()
  }
}
