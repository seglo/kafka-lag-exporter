package com.lightbend.kafkalagexporter

import com.lightbend.kafkalagexporter.Domain.Measurements
import org.scalatest._

class DomainSpec extends FlatSpec with Matchers {

  it should "calculate lag using by extrapolating offset" in {
    val now = 1000000001000L

    val a = Measurements.Single(100L, now - 1000)
    val b = Measurements.Single(200L, now)

    val measurement = Measurements.Double(a, b)

    val latestOffset = 300L

    val lag = measurement.timeLag(latestOffset)

    lag.toMillis shouldBe 1000 // 1s
  }
}
