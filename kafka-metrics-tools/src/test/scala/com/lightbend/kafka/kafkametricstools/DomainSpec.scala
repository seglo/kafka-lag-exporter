package com.lightbend.kafka.kafkametricstools

import com.lightbend.kafka.kafkametricstools.Domain.Measurements
import org.scalatest.{FlatSpec, Matchers}

class DomainSpec extends FlatSpec with Matchers {

  it should "calculate lag in seconds by extrapolating to latest offset" in {
    val now = 1000000001000L

    val a = Measurements.Single(100L, now - 1000)
    val b = Measurements.Single(200L, now)

    val measurement = Measurements.Double(a, b)

    val latestOffset = 300L

    val lag = measurement.timeLag(latestOffset)

    lag shouldBe 1.0d // 1s
  }
}
