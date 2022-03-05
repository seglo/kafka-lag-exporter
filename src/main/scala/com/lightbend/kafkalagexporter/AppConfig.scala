/*
 * Copyright (C) 2019-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import java.util

import com.lightbend.kafkalagexporter.EndpointSink.ClusterGlobalLabels
import com.typesafe.config.{Config, ConfigObject}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.compat.java8.DurationConverters._
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Try

object AppConfig {
  def apply(config: Config): AppConfig = {
    val c = config.getConfig("kafka-lag-exporter")
    val pollInterval = c.getDuration("poll-interval").toScala
    val lookupTableSize = c.getInt("lookup-table-size")

    val metricWhitelist = c.getStringList("metric-whitelist").asScala.toList

    val sinks = if (c.hasPath("sinks"))
      c.getStringList("sinks").asScala.toList
    else
      List("PrometheusEndpointSink")

    val sinkConfigs : List[SinkConfig] = sinks.flatMap { sink =>
      sink match {
        case "PrometheusEndpointSink" => Some(new PrometheusEndpointSinkConfig(sink, metricWhitelist, c))
        case "InfluxDBPusherSink" => Some(new InfluxDBPusherSinkConfig(sink, metricWhitelist, c))
        case "GraphiteEndpointSink" => Some(new GraphiteEndpointConfig(sink, metricWhitelist, c))
        case _ => None
      }
    }

    val clientGroupId = c.getString("client-group-id")
    val kafkaClientTimeout = c.getDuration("kafka-client-timeout").toScala
    val clusters = c.getConfigList("clusters").asScala.toList.map { clusterConfig =>
      val consumerProperties =
        if (clusterConfig.hasPath("consumer-properties"))
          parseKafkaClientsProperties(clusterConfig.getConfig("consumer-properties"))
        else
          Map.empty[String, String]
      val adminClientProperties =
        if (clusterConfig.hasPath("admin-client-properties"))
          parseKafkaClientsProperties(clusterConfig.getConfig("admin-client-properties"))
        else
          Map.empty[String, String]
      val labels =
        Try {
          val labels = clusterConfig.getConfig("labels")
          labels.entrySet().asScala.map(
            entry => (entry.getKey, entry.getValue.unwrapped().toString)
          ).toMap
        }.getOrElse(Map.empty[String, String])

      val groupWhitelist = if (clusterConfig.hasPath("group-whitelist"))
        clusterConfig.getStringList("group-whitelist").asScala.toList
      else KafkaCluster.GroupWhitelistDefault

      val groupBlacklist = if (clusterConfig.hasPath("group-blacklist"))
        clusterConfig.getStringList("group-blacklist").asScala.toList
      else KafkaCluster.GroupBlacklistDefault

      val topicWhitelist = if (clusterConfig.hasPath("topic-whitelist"))
        clusterConfig.getStringList("topic-whitelist").asScala.toList
      else KafkaCluster.TopicWhitelistDefault

      val topicBlacklist = if (clusterConfig.hasPath("topic-blacklist"))
        clusterConfig.getStringList("topic-blacklist").asScala.toList
      else KafkaCluster.TopicBlacklistDefault

      KafkaCluster(
        clusterConfig.getString("name"),
        clusterConfig.getString("bootstrap-brokers"),
        groupWhitelist,
        groupBlacklist,
        topicWhitelist,
        topicBlacklist,
        consumerProperties,
        adminClientProperties,
        labels
      )
    }

   val redisConfig: RedisConfig = new RedisConfig(enabled = c.getBoolean("redis.enabled"),
                                                  database = c.getInt("redis.database"),
                                                  host = c.getString("redis.host"),
                                                  port = c.getInt("redis.port"),
                                                  timeout = c.getInt("redis.timeout"),
                                                  prefix = c.getString("redis.prefix"),
                                                  separator = c.getString("redis.separator"),
                                                  resolution = c.getDuration("redis.resolution").toScala,
                                                  retention = c.getDuration("redis.retention").toScala,
                                                  expiration = c.getDuration("redis.expiration").toScala)

    val strimziWatcher = c.getString("watchers.strimzi").toBoolean

    AppConfig(pollInterval, lookupTableSize, sinkConfigs, clientGroupId, kafkaClientTimeout, clusters, redisConfig, strimziWatcher)
  }

  // Copied from Alpakka Kafka
  // https://github.com/akka/alpakka-kafka/blob/v1.0.5/core/src/main/scala/akka/kafka/internal/ConfigSettings.scala
  def parseKafkaClientsProperties(config: Config): Map[String, String] = {
    @tailrec
    def collectKeys(c: ConfigObject, processedKeys: Set[String], unprocessedKeys: List[String]): Set[String] =
      if (unprocessedKeys.isEmpty) processedKeys
      else {
        c.toConfig.getAnyRef(unprocessedKeys.head) match {
          case o: util.Map[_, _] =>
            collectKeys(c,
              processedKeys,
              unprocessedKeys.tail ::: o.keySet().asScala.toList.map(unprocessedKeys.head + "." + _))
          case _ =>
            collectKeys(c, processedKeys + unprocessedKeys.head, unprocessedKeys.tail)
        }
      }

    val keys = collectKeys(config.root, Set.empty[String], config.root().keySet().asScala.toList)
    keys.map(key => key -> config.getString(key)).toMap
  }

  def getPotentiallyInfiniteDuration(underlying: Config, path: String): Duration = underlying.getString(path) match {
    case "infinite" => Duration.Inf
    case _ => underlying.getDuration(path).toScala
  }
}

object KafkaCluster {
  val GroupWhitelistDefault = List(".*")
  val GroupBlacklistDefault = List.empty[String]
  val TopicWhitelistDefault = List(".*")
  val TopicBlacklistDefault = List.empty[String]
}

final case class KafkaCluster(name: String, bootstrapBrokers: String,
                              groupWhitelist: List[String] = KafkaCluster.GroupWhitelistDefault,
                              groupBlacklist: List[String] = KafkaCluster.GroupBlacklistDefault,
                              topicWhitelist: List[String] = KafkaCluster.TopicWhitelistDefault,
                              topicBlacklist: List[String] = KafkaCluster.TopicBlacklistDefault,
                              consumerProperties: Map[String, String] = Map.empty,
                              adminClientProperties: Map[String, String] = Map.empty,
                              labels: Map[String, String] = Map.empty) {
  override def toString(): String = {
    s"""
       |  Cluster name: $name
       |  Cluster Kafka bootstrap brokers: $bootstrapBrokers
       |  Consumer group whitelist: [${groupWhitelist.mkString(", ")}]
       |  Consumer group blacklist: [${groupBlacklist.mkString(", ")}]
       |  Topic whitelist: [${topicWhitelist.mkString(", ")}]
       |  Topic blacklist: [${topicBlacklist.mkString(", ")}]
     """.stripMargin
  }
}

object RedisConfig {
  val EnabledDefault: Boolean = false
  val DatabaseDefault: Int = 0
  val HostDefault: String = "localhost"
  val PortDefault: Int = 6379
  val TimeoutDefault: Int = 60
  val PrefixDefault: String =  "kafka-lag-exporter"
  val SeparatorDefault: String =  ":"
  val ResolutionDefault: Duration = Duration("1 minute")
  val RetentionDefault: Duration = Duration("1 day")
  val ExpirationDefault: Duration = Duration("7 days")
}

final case class RedisConfig(enabled: Boolean = RedisConfig.EnabledDefault,
                             database: Int = RedisConfig.DatabaseDefault,
                             host: String = RedisConfig.HostDefault,
                             port: Int = RedisConfig.PortDefault,
                             timeout: Int = RedisConfig.TimeoutDefault,
                             prefix: String = RedisConfig.PrefixDefault,
                             separator: String = RedisConfig.SeparatorDefault,
                             resolution: Duration = RedisConfig.ResolutionDefault,
                             retention: Duration = RedisConfig.RetentionDefault,
                             expiration: Duration = RedisConfig.ExpirationDefault) {
  override def toString(): String = {
    s"""|  Enabled: $enabled
        |  Database: $database
        |  Host: $host
        |  Port: $port
        |  Timeout: $timeout
        |  Prefix: $prefix
        |  Separator: $separator
        |  Resolution: $resolution
        |  Retention: $retention
        |  Expiration: $expiration
     """.stripMargin
  }
}

final case class AppConfig(pollInterval: FiniteDuration, 
                           lookupTableSize: Int, 
                           sinkConfigs: List[SinkConfig], 
                           clientGroupId: String, 
                           clientTimeout: FiniteDuration, 
                           clusters: List[KafkaCluster], 
                           redis: RedisConfig, 
                           strimziWatcher: Boolean) {
  override def toString(): String = {
    val clusterString =
      if (clusters.isEmpty)
        "  (none)"
      else clusters.map(_.toString).mkString("\n")
    val sinksString = sinkConfigs.mkString("")
    val redisString = redis.toString()
    s"""
       |Poll interval: $pollInterval
       |Lookup table size: $lookupTableSize
       |Metrics whitelist: [${sinkConfigs.head.metricWhitelist.mkString(", ")}]
       |Admin client consumer group id: $clientGroupId
       |Kafka client timeout: $clientTimeout
       |$sinksString
       |Statically defined Clusters:
       |$clusterString
       |Redis:
       |$redisString
       |Watchers:
       |  Strimzi: $strimziWatcher
     """.stripMargin
  }

  def clustersGlobalLabels(): ClusterGlobalLabels = {
    clusters.map { cluster =>
      cluster.name -> cluster.labels
    }.toMap
  }
}
