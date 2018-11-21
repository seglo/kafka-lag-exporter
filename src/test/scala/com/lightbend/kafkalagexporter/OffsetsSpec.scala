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

    lag shouldBe 1000 // 1s
  }
//  "A Stack" should "pop values in last-in-first-out order" in {
//    val stack = new Stack[Int]
//    stack.push(1)
//    stack.push(2)
//    stack.pop() should be (2)
//    stack.pop() should be (1)
//  }
//
//  it should "throw NoSuchElementException if an empty stack is popped" in {
//    val emptyStack = new Stack[Int]
//    a [NoSuchElementException] should be thrownBy {
//      emptyStack.pop()
//    }
//  }
}
