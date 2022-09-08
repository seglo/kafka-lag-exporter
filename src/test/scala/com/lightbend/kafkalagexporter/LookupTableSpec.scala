/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2022 Sean Glover <https://seanglover.com>
 */

package com.lightbend.kafkalagexporter

import com.lightbend.kafkalagexporter.ConsumerGroupCollector.CollectorConfig
import com.lightbend.kafkalagexporter.LookupTable.AddPointResult.{
  Inserted,
  NonMonotonic,
  OutOfOrder,
  UpdatedRetention,
  UpdatedSameOffset
}
import com.lightbend.kafkalagexporter.LookupTable.LookupResult.{
  LagIsZero,
  Prediction,
  TooFewPoints
}
import com.lightbend.kafkalagexporter.LookupTableConfig.RedisTableConfig

import java.time.{Clock, Instant, ZoneId}
import com.redis.RedisClient
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import scala.concurrent.duration.DurationInt

class LookupTableSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll {

  import com.lightbend.kafkalagexporter.LookupTable._

  private val image = DockerImageName.parse("redis").withTag("5.0.3-alpine")
  private val container: GenericContainer[_] = {
    val c = new GenericContainer(image)
    c.withExposedPorts(6379)
    c
  }

  var redisConfig: RedisTableConfig = null
  var redisClient: RedisClient = null
  var config: CollectorConfig = null
  var table: RedisTable = null

  override def beforeAll(): Unit = {
    container.start()
//    redisConfig = LookupTableConfig.RedisTableConfig(
//      resolution = 0.second,
//      retention = Long.MaxValue.nanoseconds,
//      expiration = 30.minute,
//      host = container.getHost,
//      port = container.getFirstMappedPort
//    )
    redisConfig = new LookupTableConfig.RedisTableConfig(
      ConfigFactory.parseString(s"""lookup-table.redis = {
           |  resolution = 0 seconds
           |  retention = 1 day
           |  expiration = 30 minutes
           |  host = "${container.getHost}"
           |  port = ${container.getFirstMappedPort}
           |}""".stripMargin)
    )
    redisClient = redisConfig.client
    config = ConsumerGroupCollector.CollectorConfig(
      0.second,
      redisConfig,
      KafkaCluster("default", ""),
      Clock.fixed(Instant.ofEpochMilli(0), ZoneId.systemDefault())
    )
    table = LookupTable.RedisTable(
      config.cluster.name,
      Domain.TopicPartition("topic", 0),
      redisConfig,
      Clock.fixed(Instant.EPOCH, ZoneId.of("UTC"))
    )
  }

  override def afterAll(): Unit = {
    container.stop()
  }

  "LookupTable" - {
    "RedisTable" - {
      "invalids and edge conditions" in {
        // Make sure the Point table is empty
        redisClient.del(table.pointsKey)

        if (table.length > 0) {
          fail(s"New table should be empty $table")
        }

        table.lookup(0) shouldBe TooFewPoints

        // Point(offset: Long, time: Long)
        table.addPoint(Point(100, 100))

        table.lookup(0) shouldBe TooFewPoints

        // invalid points.
        // should be monotonically increasing in time and offset
        table.addPoint(Point(110, 90))
        table.addPoint(Point(90, 110))
        table.addPoint(Point(0, 0))
        table.addPoint(Point(110, -1))
        table.addPoint(Point(-1, 110))
        table.addPoint(Point(-1, -1))

        if (table.length != 1) {
          fail(s"Expected out of order to be skipped $table")
        }
      }

      "square lookups, x == y" in {
        // Make sure the Point table is empty
        redisClient.del(table.pointsKey)

        table.addPoint(Point(100, 100)) shouldBe Inserted
        table.addPoint(Point(200, 200)) shouldBe Inserted

        table.length shouldEqual 2

        val tests = List[Long](150, 190, 110, // interpolation
          10, 0, -100, // extrapolation under the table
          300, 100 // extrapolation over the table
        )

        tests.foreach(expected =>
          table.lookup(expected) shouldBe Prediction(expected)
        )
      }

      "lookups with flat sections" in {
        // Make sure the Point table is empty
        redisClient.del(table.pointsKey)

        table.addPoint(Point(100, 30))
        table.addPoint(Point(200, 60))
        table.addPoint(Point(200, 120))
        table.addPoint(Point(200, 700))

        if (table.length != 3) {
          fail(
            s"Expected table to have 3 entries (it has ${table.length}). Table should truncate compress middle value for offset 200."
          )
        }

        table.addPoint(Point(300, 730))
        table.addPoint(Point(300, 9000))
        table.addPoint(Point(400, 9030))

        table.lookup(199) shouldBe Prediction(59.7)
        table.lookup(200) shouldBe Prediction(
          700
        ) // should find the latest (right hand side) of the flat section
        table.lookup(201) shouldBe Prediction(700.3)
        table.lookup(250) shouldBe Prediction(715)
        table.lookup(299) shouldBe Prediction(729.7)
        table.lookup(300) shouldBe Prediction(9000) // ditto
        table.lookup(301) shouldBe Prediction(9000.3)
      }

      "lookups when table only contains a flat section with offsets same as lookup" in {
        // Make sure the Point table is empty
        redisClient.del(table.pointsKey)

        table.addPoint(Point(0, 0))
        table.addPoint(Point(0, 100))

        table.lookup(0) shouldBe LagIsZero
      }

      "lookup is zero when when table has a single element the same as the last group offset" in {
        // Make sure the Point table is empty
        redisClient.del(table.pointsKey)

        table.addPoint(Point(0, 100))
        table.lookup(0) shouldBe LagIsZero
      }

      "infinite lookups, dy == 0, flat curve/no growth" in {
        // Make sure the Point table is empty
        redisClient.del(table.pointsKey)

        table.addPoint(Point(100, 100))
        table.addPoint(Point(100, 200))
        table.addPoint(Point(100, 300))
        table.addPoint(Point(100, 400))

        if (table.length != 2) {
          fail(s"Expected flat entries to compress to a single entry $table")
        }

        if (table.mostRecentPoint().right.get.time != 400) {
          fail(s"Expected compressed table to have last timestamp $table")
        }

        table.lookup(99) shouldBe Prediction(
          Double.NegativeInfinity
        )
        table.lookup(101) shouldBe Prediction(
          Double.PositiveInfinity
        )
      }

      "table retention and resolution" in {
        val _redisTableConfig = new LookupTableConfig.RedisTableConfig(
          ConfigFactory.parseString(s"""lookup-table.redis = {
               |  resolution = 1 seconds
               |  retention = 2 seconds
               |  expiration = 30 minutes
               |  host = "${container.getHost}"
               |  port = ${container.getFirstMappedPort}
               |}""".stripMargin)
        )
        val _config = ConsumerGroupCollector.CollectorConfig(
          0.second,
          _redisTableConfig,
          KafkaCluster("default", ""),
          Clock.fixed(Instant.ofEpochMilli(0), ZoneId.systemDefault())
        )
        val table = LookupTable.RedisTable(
          _config.cluster.name,
          Domain.TopicPartition("topic", 0),
          _redisTableConfig
        )

        // Make sure the Point table is empty
        redisClient.del(table.pointsKey)

        table.addPoint(
          Point(100, Clock.systemUTC().instant().toEpochMilli)
        ) shouldBe Inserted
        table.addPoint(
          Point(200, Clock.systemUTC().instant().toEpochMilli)
        ) shouldBe UpdatedRetention
        Thread.sleep(1000)
        table.addPoint(
          Point(200, Clock.systemUTC().instant().toEpochMilli)
        ) shouldBe Inserted
        Thread.sleep(1000)
        table.addPoint(
          Point(200, Clock.systemUTC().instant().toEpochMilli)
        ) shouldBe UpdatedSameOffset
        table.addPoint(
          Point(300, Clock.systemUTC().instant().toEpochMilli)
        ) shouldBe Inserted

        if (table.length != 3) {
          fail(
            s"Expected table to limit to 3 entries (current is ${table.length})"
          )
        }

        // Sleeping 1 seconds for the first point to expire
        Thread.sleep(1000)
        table.addPoint(
          Point(400, Clock.systemUTC().instant().toEpochMilli)
        ) shouldBe Inserted

        if (table.length != 3) {
          fail(
            s"Expected table to limit to 3 entries (current is ${table.length})"
          )
        }

        Thread.sleep(1000)
        table.addPoint(
          Point(500, Clock.systemUTC().instant().toEpochMilli)
        ) shouldBe Inserted

        if (table.length != 2) {
          fail(
            s"Expected table to limit to 2 entries (current is ${table.length})"
          )
        }
      }

      "normal case, steady timestamps, different val rates" in {
        // Make sure the Point table is empty
        redisClient.del(table.pointsKey)

        table.addPoint(Point(0, 0))
        table.addPoint(Point(10, 1))
        table.addPoint(Point(200, 2))
        table.addPoint(Point(3000, 3))
        table.addPoint(Point(40000, 4))

        if (table.length != 5) {
          fail(s"Expected table to limit to 5 entries $table")
        }

        table.lookup(1600) shouldBe Prediction(2.5)
        table.lookup(0) shouldBe Prediction(0.0)
        table.lookup(1) shouldBe Prediction(0.09999999999999998)
        table.lookup(9) shouldBe Prediction(0.9)
        table.lookup(10) shouldBe Prediction(1)
        table.lookup(200) shouldBe Prediction(2)
        table.lookup(2999) shouldBe Prediction(2.9996428571428573)
        table.lookup(3000) shouldBe Prediction(3)
        table.lookup(3001) shouldBe Prediction(3.000027027027027)
        table.lookup(40000) shouldBe LagIsZero
        // extrapolation
        table.lookup(-10000) shouldBe Prediction(-1)
        table.lookup(50000) shouldBe Prediction(5)
      }

      "mostRecentPoint" in {
        // Make sure the Point table is empty
        redisClient.del(table.pointsKey)

        val result = table.mostRecentPoint()
        if (result.isRight) {
          fail(
            s"Expected most recent point on empty table to fail with an error, but got $result"
          )
        }

        for (n <- 0 to 10) {
          table.addPoint(Point(n, n * 10))
          val result = table.mostRecentPoint()

          if (result.isLeft) {
            fail(
              s"Most recent point on $table returned error unexpectedly: $result"
            )
          }

          if (n != result.right.get.offset) {
            fail(
              s"Most recent point on $table expected $n, but got ${result.right.get.offset}"
            )
          }
        }
      }

      "redis return invalid results" in {
        // Make sure the Point table is empty
        redisClient.del(table.pointsKey)

        table.addPoint(Point(100, 100)) shouldBe Inserted
        table.addPoint(Point(110, 90)) shouldBe OutOfOrder
        table.addPoint(Point(90, 110)) shouldBe NonMonotonic

        table.lookup(120) shouldBe TooFewPoints
      }
    }

    "MemoryTable" - {
      "invalids and edge conditions" in {
        val tableConfig = new LookupTableConfig.MemoryTableConfig(
          ConfigFactory.parseString("lookup-table.memory.size = 10")
        )
        val table = LookupTable.MemoryTable(tableConfig)

        if (table.points.nonEmpty) {
          fail(s"New table should be empty $table")
        }

        table.lookup(0) shouldBe TooFewPoints

        // Point(offset: Long, time: Long)
        table.addPoint(Point(100, 100))

        table.lookup(0) shouldBe TooFewPoints

        // invalid points.
        // should be monotonically increasing in time and offset
        table.addPoint(Point(110, 90))
        table.addPoint(Point(90, 110))
        table.addPoint(Point(0, 0))
        table.addPoint(Point(110, -1))
        table.addPoint(Point(-1, 110))
        table.addPoint(Point(-1, -1))

        if (table.points.length != 1) {
          fail(s"Expected out of order to be skipped $table")
        }
      }

      "square lookups, x == y" in {
        val tableConfig = new LookupTableConfig.MemoryTableConfig(
          ConfigFactory.parseString("lookup-table.memory.size = 10")
        )
        val table = LookupTable.MemoryTable(tableConfig)

        table.addPoint(Point(100, 100))
        table.addPoint(Point(200, 200))

        val tests = List[Long](150, 190, 110, // interpolation
          10, 0, -100, // extrapolation under the table
          300, 100 // extrapolation over the table
        )

        tests.foreach(expected =>
          table.lookup(expected) shouldBe Prediction(expected)
        )
      }

      "lookups with flat sections" in {
        val tableConfig = new LookupTableConfig.MemoryTableConfig(
          ConfigFactory.parseString("lookup-table.memory.size = 10")
        )
        val table = LookupTable.MemoryTable(tableConfig)

        table.addPoint(Point(100, 30))
        table.addPoint(Point(200, 60))
        table.addPoint(Point(200, 120))
        table.addPoint(Point(200, 700))

        if (table.points.length != 3) {
          fail(
            s"Expected table to have 3 entries.  Table should truncate compress middle value for offset 200.  $table"
          )
        }

        table.addPoint(Point(300, 730))
        table.addPoint(Point(300, 9000))
        table.addPoint(Point(400, 9030))

        table.lookup(199) shouldBe Prediction(59.7)
        table.lookup(200) shouldBe Prediction(
          700
        ) // should find the latest (right hand side) of the flat section
        table.lookup(201) shouldBe Prediction(700.3)
        table.lookup(250) shouldBe Prediction(715)
        table.lookup(299) shouldBe Prediction(729.7)
        table.lookup(300) shouldBe Prediction(9000) // ditto
        table.lookup(301) shouldBe Prediction(9000.3)
      }

      "lookups when table only contains a flat section with offsets same as lookup" in {
        val tableConfig = new LookupTableConfig.MemoryTableConfig(
          ConfigFactory.parseString("lookup-table.memory.size = 5")
        )
        val table = LookupTable.MemoryTable(tableConfig)

        table.addPoint(Point(0, 0))
        table.addPoint(Point(0, 100))

        table.lookup(0) shouldBe LagIsZero
      }

      "lookup is zero when when table has a single element the same as the last group offset" in {
        val tableConfig = new LookupTableConfig.MemoryTableConfig(
          ConfigFactory.parseString("lookup-table.memory.size = 5")
        )
        val table = LookupTable.MemoryTable(tableConfig)
        table.addPoint(Point(0, 100))
        table.lookup(0) shouldBe LagIsZero
      }

      "infinite lookups, dy == 0, flat curve/no growth" in {
        val tableConfig = new LookupTableConfig.MemoryTableConfig(
          ConfigFactory.parseString("lookup-table.memory.size = 10")
        )
        val table = LookupTable.MemoryTable(tableConfig)

        table.addPoint(Point(100, 100))
        table.addPoint(Point(100, 200))
        table.addPoint(Point(100, 300))
        table.addPoint(Point(100, 400))

        if (table.points.length != 2) {
          fail(s"Expected flat entries to compress to a single entry $table")
        }

        if (table.points(1).time != 400) {
          fail(s"Expected compressed table to have last timestamp $table")
        }

        table.lookup(99) shouldBe Prediction(Double.NegativeInfinity)
        table.lookup(101) shouldBe Prediction(Double.PositiveInfinity)
      }

      "normal case, table truncates, steady timestamps, different val rates" in {
        val tableConfig = new LookupTableConfig.MemoryTableConfig(
          ConfigFactory.parseString("lookup-table.memory.size = 5")
        )
        val table = LookupTable.MemoryTable(tableConfig)

        table.addPoint(Point(-2, -2))
        table.addPoint(Point(-1, -1))
        table.addPoint(Point(0, 0))
        table.addPoint(Point(10, 1))
        table.addPoint(Point(200, 2))

        if (table.points.length != 5) {
          fail(s"Expected table to have 5 entries $table")
        }

        table.addPoint(Point(3000, 3))
        table.addPoint(Point(40000, 4))

        if (table.points.length != 5) {
          fail(s"Expected table to limit to 5 entries $table")
        }

        table.lookup(1600) shouldBe Prediction(2.5)
        table.lookup(0) shouldBe Prediction(0.0)
        table.lookup(1) shouldBe Prediction(0.09999999999999998)
        table.lookup(9) shouldBe Prediction(0.9)
        table.lookup(10) shouldBe Prediction(1)
        table.lookup(200) shouldBe Prediction(2)
        table.lookup(2999) shouldBe Prediction(2.9996428571428573)
        table.lookup(3000) shouldBe Prediction(3)
        table.lookup(3001) shouldBe Prediction(3.000027027027027)
        table.lookup(40000) shouldBe LagIsZero
        // extrapolation
        table.lookup(-10000) shouldBe Prediction(-1)
        table.lookup(50000) shouldBe Prediction(5)
      }

      "mostRecentPoint" in {
        val tableConfig = new LookupTableConfig.MemoryTableConfig(
          ConfigFactory.parseString("lookup-table.memory.size = 5")
        )
        val table = LookupTable.MemoryTable(tableConfig)

        val result = table.mostRecentPoint()

        if (result.isRight) {
          fail(
            s"Expected most recent point on empty table to fail with an error, but got $result"
          )
        }

        for (n <- 0 to 10) {
          table.addPoint(Point(n, n * 10))
          val result = table.mostRecentPoint()

          if (result.isLeft) {
            fail(
              s"Most recent point on $table returned error unexpectedly: $result"
            )
          }

          if (n != result.right.get.offset) {
            fail(
              s"Most recent point on $table expected $n, but got ${result.right.get.offset}"
            )
          }
        }
      }
    }
  }
}
