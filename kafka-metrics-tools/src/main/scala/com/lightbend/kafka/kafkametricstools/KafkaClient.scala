package com.lightbend.kafka.kafkametricstools

import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.{lang, util}

import com.lightbend.kafka.kafkametricstools.Domain.Measurements
import com.lightbend.kafka.kafkametricstools.KafkaClient.KafkaClientContract
import org.apache.kafka.clients.admin._
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer, OffsetAndMetadata}
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.{KafkaFuture, TopicPartition => KafkaTopicPartition}

import scala.collection.JavaConverters._
import scala.compat.java8.DurationConverters._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try

object KafkaClient {
  val AdminClientConfigRetries = 0 // fail faster when there are transient connection errors, use supervision strategy for backoff
  val CommonClientConfigRetryBackoffMs = 1000 // longer interval between retry attempts so we don't overload clusters (default = 100ms)
  val ConsumerConfigAutoCommit = false

  def apply(bootstrapBrokers: String, groupId: String, clientTimeout: FiniteDuration)(implicit ec: ExecutionContext): KafkaClientContract =
    new KafkaClient(bootstrapBrokers, groupId, clientTimeout)(ec)

  trait KafkaClientContract {
    def getGroups(): Future[List[Domain.ConsumerGroup]]
    def getGroupOffsets(now: Long, groups: List[Domain.ConsumerGroup]): Future[Map[Domain.GroupTopicPartition, Measurements.Measurement]]
    def getLatestOffsets(now: Long, groups: List[Domain.ConsumerGroup]): Try[Map[Domain.TopicPartition, Measurements.Single]]
    def getLatestOffsets(now: Long, topicPartitions: Set[Domain.TopicPartition]): Try[Map[Domain.TopicPartition, Measurements.Single]]
    def close(): Unit
  }

  private def kafkaFuture[T](kafkaFuture: KafkaFuture[T])(implicit ec: ExecutionContext, clientTimeout: Duration): Future[T] = {
    val p = Promise[T]()
    Future {
      try {
        p.success(kafkaFuture.get(clientTimeout.toMillis, TimeUnit.MILLISECONDS))
      } catch {
        case ex: Throwable => p.failure(ex)
      }
    }(ec)
    p.future
  }

  private def createAdminClient(brokers: String, clientTimeout: Duration): AdminClient = {
    val props = new Properties()
    // AdminClient config: https://kafka.apache.org/documentation/#adminclientconfigs
    props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
    props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, clientTimeout.toMillis.toString)
    props.put(AdminClientConfig.RETRIES_CONFIG, AdminClientConfigRetries.toString)
    props.put(AdminClientConfig.RETRY_BACKOFF_MS_CONFIG, CommonClientConfigRetryBackoffMs.toString)
    AdminClient.create(props)
  }

  private def createConsumerClient(brokers: String, groupId: String, clientTimeout: Duration): KafkaConsumer[String, String] = {
    val props = new Properties()
    val deserializer = (new StringDeserializer).getClass.getName
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, ConsumerConfigAutoCommit.toString)
    //props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "30000")
    props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, clientTimeout.toMillis.toString)
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, deserializer)
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, deserializer)
    props.put(ConsumerConfig.RETRY_BACKOFF_MS_CONFIG, CommonClientConfigRetryBackoffMs.toString)
    new KafkaConsumer(props)
  }

  // extension methods to convert between Offsets.TopicPartition and org.apache.kafka.common.TopicPartition
  private implicit class OffsetsTopicPartitionOps(ktp: KafkaTopicPartition) {
    def asDomain: Domain.TopicPartition = Domain.TopicPartition(ktp.topic(), ktp.partition())
  }

  private implicit class KafkaTopicPartitionOps(tp: Domain.TopicPartition) {
    def asKafka: KafkaTopicPartition = new KafkaTopicPartition(tp.topic, tp.partition)
  }
}

class KafkaClient private(bootstrapBrokers: String, groupId: String, clientTimeout: FiniteDuration)
                         (implicit ec: ExecutionContext) extends KafkaClientContract {
  import KafkaClient._

  private implicit val _clientTimeout: Duration = clientTimeout.toJava

  private lazy val adminClient = createAdminClient(bootstrapBrokers, _clientTimeout)
  private lazy val consumer = createConsumerClient(bootstrapBrokers, groupId, _clientTimeout)

  private lazy val listGroupOptions = new ListConsumerGroupsOptions().timeoutMs(_clientTimeout.toMillis().toInt)
  private lazy val describeGroupOptions = new DescribeConsumerGroupsOptions().timeoutMs(_clientTimeout.toMillis().toInt)
  private lazy val listConsumerGroupsOptions = new ListConsumerGroupOffsetsOptions().timeoutMs(_clientTimeout.toMillis().toInt)

  /**
    * Get a list of consumer groups
    */
  def getGroups(): Future[List[Domain.ConsumerGroup]] = {
    for {
      groups <- kafkaFuture(adminClient.listConsumerGroups(listGroupOptions).all())
      groupIds = groups.asScala.map(_.groupId()).toList
      groupDescriptions <- kafkaFuture(adminClient.describeConsumerGroups(groupIds.asJava, describeGroupOptions).all())
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


  /**
    * Get latest offsets for a set of consumer groups.
    */
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
    val offsets: util.Map[KafkaTopicPartition, lang.Long] = consumer.endOffsets(topicPartitions.map(_.asKafka).asJava, _clientTimeout)
    topicPartitions.map(tp => tp -> Measurements.Single(offsets.get(tp.asKafka).toLong,now)).toMap
  }

  /**
    * Get last committed Consumer Group offsets for all group topic partitions.  When a topic partition has no matched
    * Consumer Group offset then a default offset of 0 is provided.
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
        kafkaFuture(adminClient
          .listConsumerGroupOffsets(group.id, listConsumerGroupsOptions)
          .partitionsToOffsetAndMetadata())
          .map(offsetMap => actualGroupOffsets(group, offsetMap.asScala.toMap))
      }
    }.map(_.flatten.toMap)
  }

  def close(): Unit = {
    adminClient.close(_clientTimeout.toMillis, TimeUnit.MILLISECONDS)
    consumer.close(_clientTimeout)
  }
}
