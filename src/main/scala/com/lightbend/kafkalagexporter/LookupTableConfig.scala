/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2022 Sean Glover <https://seanglover.com>
 */

package com.lightbend.kafkalagexporter

import com.redis.RedisClientPool
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

    val table: Config = config.getConfig("lookup-table.memory")
    val size: Int =
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
    val MaxIdleDefault: Int = 1
    val MaxConnectionsDefault: Int = RedisClientPool.UNLIMITED_CONNECTIONS
    val PoolWaitTimeoutDefault: Long = 3000L
  }

  final class RedisTableConfig(config: Config) extends LookupTableConfig {
    import RedisTableConfig._

    val redis: Config = config.getConfig("lookup-table.redis")
    val database: Int =
      if (redis.hasPath("database"))
        redis.getInt("database")
      else DatabaseDefault

    val host: String =
      if (redis.hasPath("host"))
        redis.getString("host")
      else HostDefault

    val port: Int =
      if (redis.hasPath("port"))
        redis.getInt("port")
      else PortDefault

    val timeout: Duration =
      if (redis.hasPath("timeout"))
        redis.getDuration("timeout").toScala
      else TimeoutDefault

    val prefix: String =
      if (redis.hasPath("prefix"))
        redis.getString("prefix")
      else PrefixDefault

    val separator: String =
      if (redis.hasPath("separator"))
        redis.getString("separator")
      else SeparatorDefault

    val retention: Duration =
      if (redis.hasPath("retention"))
        redis.getDuration("retention").toScala
      else RetentionDefault

    val expiration: Duration =
      if (redis.hasPath("expiration"))
        redis.getDuration("expiration").toScala
      else ExpirationDefault

    val maxIdle: Int =
      if (redis.hasPath("maxIdle"))
        redis.getInt("maxIdle")
      else MaxIdleDefault

    val maxConnections: Int =
      if (redis.hasPath("maxConnections"))
        redis.getInt("maxConnections")
      else MaxConnectionsDefault

    val poolWaitTimeout: Long =
      if (redis.hasPath("poolWaitTimeout"))
        redis.getLong("poolWaitTimeout")
      else PoolWaitTimeoutDefault

    val clients = new RedisClientPool(
      host = host,
      port = port,
      maxIdle = maxIdle,
      database = database,
      timeout = timeout.toSeconds.toInt,
      maxConnections = maxConnections,
      poolWaitTimeout = poolWaitTimeout
    )

    override def close(): Unit = {
      clients.close()
    }

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
         |  MaxIdle: $maxIdle
         |  MaxConnections: $maxConnections
         |  PoolWaitTimeout: $poolWaitTimeout
     """.stripMargin
    }
  }

}
