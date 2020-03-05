/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import java.util

import com.lightbend.kafkalagexporter.PrometheusEndpointSink.ClusterGlobalLabels
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
    val sinkType = if (c.hasPath("sink"))
      c.getString("sink")
    else "PrometheusEndpointSink"

    val sinkConfig = sinkType match {
      case "PrometheusEndpointSink" => new PrometheusEndpointSinkConfig(sinkType, metricWhitelist, c)
      case "InfluxDBPusherSink" => new InfluxDBPusherSinkConfig(sinkType, metricWhitelist, c)
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
        topicWhitelist,
        topicBlacklist,
        consumerProperties,
        adminClientProperties,
        labels
      )
    }
    val strimziWatcher = c.getString("watchers.strimzi").toBoolean

    AppConfig(pollInterval, lookupTableSize, sinkConfig, clientGroupId, kafkaClientTimeout, clusters, strimziWatcher)
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
  val TopicWhitelistDefault = List(".*")
  val TopicBlacklistDefault = List.empty[String]
}

final case class KafkaCluster(name: String, bootstrapBrokers: String,
                              groupWhitelist: List[String] = KafkaCluster.GroupWhitelistDefault,
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
       |  Topic whitelist: [${topicWhitelist.mkString(", ")}]
       |  Topic blacklist: [${topicBlacklist.mkString(", ")}]
     """.stripMargin
  }
}
final case class AppConfig(pollInterval: FiniteDuration, lookupTableSize: Int, sinkConfig: SinkConfig, clientGroupId: String,
                           clientTimeout: FiniteDuration, clusters: List[KafkaCluster], strimziWatcher: Boolean) {
  override def toString(): String = {
    val clusterString =
      if (clusters.isEmpty)
        "  (none)"
      else clusters.map(_.toString).mkString("\n")
    s"""
       |Poll interval: $pollInterval
       |Lookup table size: $lookupTableSize
       |Sink : $sinkConfig.sinkType
       |Sink metrics whitelist: [${sinkConfig.metricWhitelist.mkString(", ")}]
       |Admin client consumer group id: $clientGroupId
       |Kafka client timeout: $clientTimeout
       |Statically defined Clusters:
       |$clusterString
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
