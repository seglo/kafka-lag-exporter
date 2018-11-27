package com.lightbend.kafkalagexporter

import java.time.Instant

import com.lightbend.kafkalagexporter.Offsets.Single

import collection.mutable.Stack
import org.scalatest._

class OffsetsSpec extends FlatSpec with Matchers {

  it should "calculate lag using by extrapolating offset" in {
    val now = 1000000001000L

    val a = Offsets.Single(100L, now - 1000)
    val b = Offsets.Single(200L, now)

    val measurement = Offsets.Double(a, b)

    val latestOffset = 300L

    val lag = measurement.lag(now, latestOffset)

    lag.toMillis shouldBe 1000 // 1s
  }
}
