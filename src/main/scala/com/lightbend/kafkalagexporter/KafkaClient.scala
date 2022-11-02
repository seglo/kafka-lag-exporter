/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2022 Sean Glover <https://seanglover.com>
 */

package com.lightbend.kafkalagexporter

import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.{lang, util}

import com.lightbend.kafkalagexporter.Domain.{GroupOffsets, PartitionOffsets}
import com.lightbend.kafkalagexporter.KafkaClient.{
  AdminKafkaClientContract,
  ConsumerKafkaClientContract,
  KafkaClientContract
}
import org.apache.kafka.clients.admin._
import org.apache.kafka.clients.consumer.{
  ConsumerConfig,
  KafkaConsumer,
  OffsetAndMetadata
}
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.{
  KafkaFuture,
  TopicPartition => KafkaTopicPartition
}

import scala.collection.JavaConverters._
import scala.collection.immutable.Map
import scala.compat.java8.DurationConverters._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try

object KafkaClient {
  val CommonClientConfigRetryBackoffMs =
    1000 // longer interval between retry attempts so we don't overload clusters (default = 100ms)
  val ConsumerConfigAutoCommit = false

  def apply(
      cluster: KafkaCluster,
      groupId: String,
      clientTimeout: FiniteDuration,
      retries: Int
  )(implicit ec: ExecutionContext): KafkaClientContract = {
    val consumer = new ConsumerKafkaClient(
      createConsumerClient(cluster, groupId, clientTimeout),
      clientTimeout
    )
    val adminKafkaClient = new AdminKafkaClient(
      createAdminClient(cluster, clientTimeout, retries),
      clientTimeout
    )
    new KafkaClient(cluster, consumer, adminKafkaClient)(ec)
  }

  trait KafkaClientContract {
    def getGroups(): Future[(List[String], List[Domain.GroupTopicPartition])]
    def getGroupOffsets(
        now: Long,
        groups: List[String],
        groupTopicPartitions: List[Domain.GroupTopicPartition]
    ): Future[GroupOffsets]
    def getEarliestOffsets(
        now: Long,
        topicPartitions: Set[Domain.TopicPartition]
    ): Try[PartitionOffsets]
    def getLatestOffsets(
        now: Long,
        topicPartitions: Set[Domain.TopicPartition]
    ): Try[PartitionOffsets]
    def close(): Unit
  }

  private def kafkaFuture[T](
      kafkaFuture: KafkaFuture[T]
  )(implicit ec: ExecutionContext, clientTimeout: Duration): Future[T] = {
    val p = Promise[T]()
    Future {
      try {
        p.success(
          kafkaFuture.get(clientTimeout.toMillis, TimeUnit.MILLISECONDS)
        )
      } catch {
        case ex: Throwable => p.failure(ex)
      }
    }(ec)
    p.future
  }

  private def createAdminClient(
      cluster: KafkaCluster,
      clientTimeout: FiniteDuration,
      retries: Int
  ): AdminClient = {
    val props = new Properties()
    // AdminClient config: https://kafka.apache.org/documentation/#adminclientconfigs
    cluster.adminClientProperties foreach { case (k, v) =>
      props.setProperty(k, v)
    }
    props.put(
      AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
      cluster.bootstrapBrokers
    )
    props.put(
      AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,
      clientTimeout.toMillis.toString
    )
    props.put(
      AdminClientConfig.RETRIES_CONFIG,
      retries.toString
    )
    props.put(
      AdminClientConfig.RETRY_BACKOFF_MS_CONFIG,
      CommonClientConfigRetryBackoffMs.toString
    )
    AdminClient.create(props)
  }

  private def createConsumerClient(
      cluster: KafkaCluster,
      groupId: String,
      clientTimeout: FiniteDuration
  ): KafkaConsumer[Byte, Byte] = {
    val props = new Properties()
    val deserializer = (new ByteArrayDeserializer).getClass.getName
    // KafkaConsumer config: https://kafka.apache.org/documentation/#consumerconfigs
    cluster.consumerProperties foreach { case (k, v) =>
      props.setProperty(k, v)
    }
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapBrokers)
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
    props.put(
      ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
      ConsumerConfigAutoCommit.toString
    )
    // props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "30000")
    props.put(
      ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG,
      clientTimeout.toMillis.toString
    )
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, deserializer)
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, deserializer)
    props.put(
      ConsumerConfig.RETRY_BACKOFF_MS_CONFIG,
      CommonClientConfigRetryBackoffMs.toString
    )
    new KafkaConsumer(props)
  }

  // extension methods to convert between Offsets.TopicPartition and org.apache.kafka.common.TopicPartition
  private[kafkalagexporter] implicit class OffsetsTopicPartitionOps(
      ktp: KafkaTopicPartition
  ) {
    def asDomain: Domain.TopicPartition =
      Domain.TopicPartition(ktp.topic(), ktp.partition())
  }

  private[kafkalagexporter] implicit class KafkaTopicPartitionOps(
      tp: Domain.TopicPartition
  ) {
    def asKafka: KafkaTopicPartition =
      new KafkaTopicPartition(tp.topic, tp.partition)
  }

  /** AdminClient wrapper. Encapsulates calls to `AdminClient`. This abstraction
    * exists so the `AdminClient` can be be mocked in tests because the various
    * `*Result` types that are returned cannot be mocked.
    */
  trait AdminKafkaClientContract {
    def listConsumerGroups(): Future[util.Collection[ConsumerGroupListing]]
    def describeConsumerGroups(
        groupIds: List[String]
    ): Future[util.Map[String, ConsumerGroupDescription]]
    def listConsumerGroupOffsets(
        group: String
    ): Future[util.Map[KafkaTopicPartition, OffsetAndMetadata]]
    def close(): Unit
  }

  class AdminKafkaClient private[kafkalagexporter] (
      client: AdminClient,
      clientTimeout: FiniteDuration
  )(implicit ec: ExecutionContext)
      extends AdminKafkaClientContract {
    private implicit val _clientTimeout: Duration = clientTimeout.toJava

    private val listGroupOptions =
      new ListConsumerGroupsOptions().timeoutMs(_clientTimeout.toMillis.toInt)
    private val describeGroupOptions = new DescribeConsumerGroupsOptions()
      .timeoutMs(_clientTimeout.toMillis.toInt)
    private val listConsumerGroupsOptions =
      new ListConsumerGroupOffsetsOptions().timeoutMs(
        _clientTimeout.toMillis.toInt
      )

    def listConsumerGroups(): Future[util.Collection[ConsumerGroupListing]] =
      kafkaFuture(client.listConsumerGroups(listGroupOptions).all())
    def describeConsumerGroups(
        groupIds: List[String]
    ): Future[util.Map[String, ConsumerGroupDescription]] =
      kafkaFuture(
        client
          .describeConsumerGroups(groupIds.asJava, describeGroupOptions)
          .all()
      )
    def listConsumerGroupOffsets(
        group: String
    ): Future[util.Map[KafkaTopicPartition, OffsetAndMetadata]] =
      kafkaFuture(
        client
          .listConsumerGroupOffsets(group, listConsumerGroupsOptions)
          .partitionsToOffsetAndMetadata()
      )
    def close(): Unit = client.close(_clientTimeout)
  }

  trait ConsumerKafkaClientContract {
    def beginningOffsets(
        partitions: util.Collection[KafkaTopicPartition]
    ): util.Map[KafkaTopicPartition, java.lang.Long]
    def endOffsets(
        partitions: util.Collection[KafkaTopicPartition]
    ): util.Map[KafkaTopicPartition, java.lang.Long]
    def close(): Unit
  }

  class ConsumerKafkaClient private[kafkalagexporter] (
      consumer: KafkaConsumer[Byte, Byte],
      clientTimeout: FiniteDuration
  ) extends ConsumerKafkaClientContract {
    private val _clientTimeout: Duration = clientTimeout.toJava

    def beginningOffsets(
        partitions: util.Collection[KafkaTopicPartition]
    ): util.Map[KafkaTopicPartition, java.lang.Long] =
      consumer.beginningOffsets(partitions, _clientTimeout)
    def endOffsets(
        partitions: util.Collection[KafkaTopicPartition]
    ): util.Map[KafkaTopicPartition, java.lang.Long] =
      consumer.endOffsets(partitions, _clientTimeout)
    def close(): Unit = consumer.close(_clientTimeout)
  }

}

class KafkaClient private[kafkalagexporter] (
    cluster: KafkaCluster,
    consumer: ConsumerKafkaClientContract,
    adminClient: AdminKafkaClientContract
)(implicit ec: ExecutionContext)
    extends KafkaClientContract {
  import KafkaClient._

  /** Get a list of consumer groups
    */
  def getGroups(): Future[(List[String], List[Domain.GroupTopicPartition])] = {
    for {
      groups <- adminClient.listConsumerGroups()
      groupIds = getGroupIds(groups)
      groupDescriptions <- adminClient.describeConsumerGroups(groupIds)
      noMemberGroups = groupDescriptions.asScala.filter { case (_, d) =>
        d.members().isEmpty
      }.keys
      noMemberGroupsPartitionsInfo <- Future.sequence(
        noMemberGroups.map(g => getGroupPartitionsInfo(g))
      )
    } yield {
      val gtps = groupDescriptions.asScala.flatMap { case (id, desc) =>
        groupTopicPartitions(id, desc)
      }.toList
      val gtpsNoMembers = noMemberGroupsPartitionsInfo.flatten
      (groupIds, gtps ++ gtpsNoMembers)
    }
  }

  /** Retrieve partitions by consumer group ID. This is used in case when
    * members info is unavailable for the group.
    */
  def getGroupPartitionsInfo(
      groupId: String
  ): Future[List[Domain.GroupTopicPartition]] = {
    for {
      offsetsMap <- adminClient.listConsumerGroupOffsets(groupId)
      topicPartitions = offsetsMap.keySet.asScala.toList
    } yield topicPartitions.map(ktp =>
      Domain.GroupTopicPartition(
        groupId,
        "unknown",
        "unknown",
        "unknown",
        ktp.topic(),
        ktp.partition()
      )
    )
  }

  private[kafkalagexporter] def getGroupIds(
      groups: util.Collection[ConsumerGroupListing]
  ): List[String] =
    groups.asScala
      .map(_.groupId())
      .toList
      .filter(g =>
        cluster.groupWhitelist.exists(r =>
          g.matches(r)
        ) && !cluster.groupBlacklist.exists(r => g.matches(r))
      )

  private[kafkalagexporter] def groupTopicPartitions(
      groupId: String,
      desc: ConsumerGroupDescription
  ): List[Domain.GroupTopicPartition] = {
    val groupTopicPartitions = for {
      member <- desc.members().asScala
      ktp <- member.assignment().topicPartitions().asScala
      if cluster.topicWhitelist.exists(r => ktp.topic().matches(r))
      if !cluster.topicBlacklist.exists(r => ktp.topic().matches(r))
    } yield Domain.GroupTopicPartition(
      groupId,
      member.clientId(),
      member.consumerId(),
      member.host(),
      ktp.topic(),
      ktp.partition()
    )
    groupTopicPartitions.toList
  }

  /** Get earliest offsets for a set of topic partitions.
    */
  def getEarliestOffsets(
      now: Long,
      topicPartitions: Set[Domain.TopicPartition]
  ): Try[PartitionOffsets] = Try {
    val offsets: util.Map[KafkaTopicPartition, lang.Long] =
      consumer.beginningOffsets(topicPartitions.map(_.asKafka).asJava)
    topicPartitions
      .map(tp => tp -> LookupTable.Point(offsets.get(tp.asKafka).toLong, now))
      .toMap
  }

  /** Get latest offsets for a set of topic partitions.
    */
  def getLatestOffsets(
      now: Long,
      topicPartitions: Set[Domain.TopicPartition]
  ): Try[PartitionOffsets] = Try {
    val offsets: util.Map[KafkaTopicPartition, lang.Long] =
      consumer.endOffsets(topicPartitions.map(_.asKafka).asJava)
    topicPartitions
      .map(tp => tp -> LookupTable.Point(offsets.get(tp.asKafka).toLong, now))
      .toMap
  }

  /** Get last committed Consumer Group offsets for all group topic partitions
    * given a list of consumer groups. When a topic partition has no matched
    * Consumer Group offset then a default of None is provided.
    * @return
    *   A series of Future's for Consumer Group offsets requests to Kafka.
    */
  def getGroupOffsets(
      now: Long,
      groups: List[String],
      allGtps: List[Domain.GroupTopicPartition]
  ): Future[GroupOffsets] = {
    val groupOffsetsF: Future[List[GroupOffsets]] = Future.sequence {
      groups.map { group =>
        val gtps = allGtps.filter(_.id == group)
        adminClient
          .listConsumerGroupOffsets(group)
          .map(offsetMap => getGroupOffsets(now, gtps, offsetMap.asScala.toMap))
      }
    }

    groupOffsetsF
      .map(_.flatten.toMap)
      .map(go => getOffsetOrZero(allGtps, go))
  }

  /** Call to `AdminClient` to get group offset info. This is only its own
    * method so it can be mocked out in a test because it's not possible to
    * instantiate or mock the `ListConsumerGroupOffsetsResult` type for some
    * reason.
    */

  /** Backfill any group topic partitions with no offset as None
    */
  private[kafkalagexporter] def getOffsetOrZero(
      gtps: List[Domain.GroupTopicPartition],
      groupOffsets: GroupOffsets
  ): GroupOffsets =
    gtps.map(gtp => gtp -> groupOffsets.getOrElse(gtp, None)).toMap

  /** Transform Kafka response into `GroupOffsets`
    */
  private[kafkalagexporter] def getGroupOffsets(
      now: Long,
      gtps: List[Domain.GroupTopicPartition],
      offsetMap: Map[KafkaTopicPartition, OffsetAndMetadata]
  ): GroupOffsets =
    (for {
      gtp <- gtps
      offsetResult <- offsetMap.get(gtp.tp.asKafka)
    } yield {
      // Offset can be null if it's invalid.  See javadocs:
      // https://kafka.apache.org/25/javadoc/org/apache/kafka/clients/admin/ListConsumerGroupOffsetsResult.html#partitionsToOffsetAndMetadata--
      if (offsetResult == null)
        gtp -> None
      else
        gtp -> Some(LookupTable.Point(offsetResult.offset(), now))
    }).toMap

  def close(): Unit = {
    adminClient.close()
    consumer.close()
  }
}
