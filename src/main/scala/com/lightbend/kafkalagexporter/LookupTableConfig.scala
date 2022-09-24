/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2022 Sean Glover <https://seanglover.com>
 */

package com.lightbend.kafkalagexporter

import com.redis.RedisClient
import com.typesafe.config.Config

import scala.compat.java8.DurationConverters.DurationOps
import scala.concurrent.duration.{Duration, DurationInt}

sealed trait LookupTableConfig {
  def close(): Unit = ()
}

object LookupTableConfig {
  def apply(config: Config): LookupTableConfig = {
    if (config.hasPath("lookup-table.redis")) {
      new RedisTableConfig(config)
    } else {
      new MemoryTableConfig(config)
    }
  }

  object MemoryTableConfig {
    val SizeDefault = 60
  }

  final class MemoryTableConfig(config: Config) extends LookupTableConfig {
    import MemoryTableConfig._

    val table = config.getConfig("lookup-table.memory")
    val size =
      if (table.hasPath("size")) table.getInt("size")
      else SizeDefault

    override def toString: String = {
      s"""Memory Lookup Table:
         |  Size: $size
     """.stripMargin
    }
  }

  object RedisTableConfig {
    val DatabaseDefault: Int = 0
    val HostDefault: String = "localhost"
    val PortDefault: Int = 6379
    val TimeoutDefault: Duration = 60.seconds
    val PrefixDefault: String = "kafka-lag-exporter"
    val SeparatorDefault: String = ":"
    val RetentionDefault: Duration = 1.day
    val ExpirationDefault: Duration = 1.day
  }

  final class RedisTableConfig(config: Config) extends LookupTableConfig {
    import RedisTableConfig._

    val redis = config.getConfig("lookup-table.redis")
    val database =
      if (redis.hasPath("database"))
        redis.getInt("database")
      else DatabaseDefault

    val host =
      if (redis.hasPath("host"))
        redis.getString("host")
      else HostDefault

    val port =
      if (redis.hasPath("port"))
        redis.getInt("port")
      else PortDefault

    val timeout =
      if (redis.hasPath("timeout"))
        redis.getDuration("timeout").toScala
      else TimeoutDefault

    val prefix =
      if (redis.hasPath("prefix"))
        redis.getString("prefix")
      else PrefixDefault

    val separator =
      if (redis.hasPath("separator"))
        redis.getString("separator")
      else SeparatorDefault

    val retention =
      if (redis.hasPath("retention"))
        redis.getDuration("retention").toScala
      else RetentionDefault

    val expiration =
      if (redis.hasPath("expiration"))
        redis.getDuration("expiration").toScala
      else ExpirationDefault

    val client = new RedisClient(
      database = database,
      host = host,
      port = port,
      timeout = timeout.toSeconds.toInt
    )

    override def close(): Unit = client.close()

    override def toString: String = {
      s"""Redis Lookup Table:
         |  Database: $database
         |  Host: $host
         |  Port: $port
         |  Timeout: $timeout
         |  Prefix: $prefix
         |  Separator: $separator
         |  Retention: $retention
         |  Expiration: $expiration
     """.stripMargin
    }
  }

}
