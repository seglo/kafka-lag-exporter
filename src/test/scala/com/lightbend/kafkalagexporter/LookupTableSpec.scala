/*
 * Copyright (C) 2019-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import com.lightbend.kafkalagexporter.LookupTable.Table.{LagIsZero, Prediction, TooFewPoints}
import org.scalatest.{FreeSpec, Matchers}

class LookupTableSpec extends FreeSpec with Matchers {

  import com.lightbend.kafkalagexporter.LookupTable._

  "LookupTable" - {
    "lookupOffset" - {
      "invalids and edge conditions" in {
        val table = Table(10)

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
        val table = Table(10)

        table.addPoint(Point(100, 100))
        table.addPoint(Point(200, 200))

        val tests = List[Long](150, 190, 110, // interpolation
        10, 0, -100, // extrapolation under the table
        300, 100 // extrapolation over the table
        )

        tests.foreach(expected => table.lookup(expected) shouldBe Prediction(expected))
      }

      "lookups with flat sections" in {
        val table = Table(10)

        table.addPoint(Point(100, 30))
        table.addPoint(Point(200, 60))
        table.addPoint(Point(200, 120))
        table.addPoint(Point(200, 700))

        if (table.points.length != 3) {
          fail(s"Expected table to have 3 entries.  Table should truncate compress middle value for offset 200.  $table")
        }

        table.addPoint(Point(300, 730))
        table.addPoint(Point(300, 9000))
        table.addPoint(Point(400, 9030))

        table.lookup(199) shouldBe Prediction(59.7)
        table.lookup(200) shouldBe Prediction(700) // should find the latest (right hand side) of the flat section
        table.lookup(201) shouldBe Prediction(700.3)
        table.lookup(250) shouldBe Prediction(715)
        table.lookup(299) shouldBe Prediction(729.7)
        table.lookup(300) shouldBe Prediction(9000) // ditto
        table.lookup(301) shouldBe Prediction(9000.3)
      }

      "lookups when table only contains a flat section with offsets same as lookup" in {
        val table = Table(5)

        table.addPoint(Point(0, 0))
        table.addPoint(Point(0, 100))

        table.lookup(0) shouldBe LagIsZero
      }

      "lookup is zero when when table has a single element the same as the last group offset" in {
        val table = Table(5)
        table.addPoint(Point(0, 100))
        table.lookup(0) shouldBe LagIsZero
      }

      "infinite lookups, dy == 0, flat curve/no growth" in {
        val table = Table(10)

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
        val table = Table(5)

        table.addPoint(Point(-2, -2))
        table.addPoint(Point(-1 , -1))
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
    }

    "mostRecentPoint" in {
      val table = Table(5)

      val result = table.mostRecentPoint()

      if (result.isRight) {
        fail(s"Expected most recent point on empty table to fail with an error, but got $result")
      }

      for (n <- 0 to 10) {
        table.addPoint(Point(n, n*10))
        val result = table.mostRecentPoint()

        if (result.isLeft) {
          fail(s"Most recent point on $table returned error unexpectedly: $result")
        }

        if (n != result.right.get.offset) {
          fail(s"Most recent point on $table expected $n, but got ${result.right.get.offset}")
        }
      }
    }
  }
}