/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2022 Sean Glover <https://seanglover.com>
 */

package com.lightbend.kafkalagexporter

import scala.collection.mutable

object LookupTable {
  case class Point(offset: Long, time: Long)
  case class Table(limit: Int, points: mutable.Queue[Point]) {
    import Table._

    /**
     * Add the `Point` to the table.
     */
    def addPoint(point: Point): Unit = mostRecentPoint() match {
      // new point is out of order
      case Right(mrp) if mrp.time >= point.time =>
      // new point is not part of a monotonically increasing set
      case Right(mrp) if mrp.offset > point.offset =>
      // compress flat lines to a single segment
      // rather than run the table into a flat line, just move the right hand side out until we see variation again
      // Ex)
      //   Table contains:
      //     Point(offset = 200, time = 1000)
      //     Point(offset = 200, time = 2000)
      //   Add another entry for offset = 200
      //     Point(offset = 200, time = 3000)
      //   If we still have not incremented the offset, replace the last entry.  Table now looks like:
      //     Point(offset = 200, time = 1000)
      //     Point(offset = 200, time = 3000)
      case Right(mrp) if mrp.offset == point.offset &&
                         points.length > 1 &&
                         points(points.length - 2).offset == point.offset =>
        // update the most recent point
        points(points.length - 1) = point
      // the table is empty or we filtered thru previous cases on the most recent point
      case _ =>
        // dequeue oldest point if we've hit the limit (sliding window)
        if (points.length == limit) points.dequeue()
        points.enqueue(point)
    }

    /**
     * Predict the timestamp of a provided offset using interpolation if in the sliding window, or extrapolation if
     * outside the sliding window.
     */
    def lookup(offset: Long): Result = {
      def estimate(): Result = {
        // search for two cells that contains the given offset
        val (left, right) = points
          .reverseIterator
          // create a sliding window of 2 elements with a step size of 1
          .sliding(size = 2, step = 1)
          // convert window to a tuple. since we're iterating backwards we match right and left in reverse.
          .map { case r :: l :: Nil => (l, r) }
          // find the Point that contains the offset
          .find { case (l, r) => offset >= l.offset && offset <= r.offset }
          // offset is not between any two points in the table
          // extrapolate given largest trendline we have available
          .getOrElse {
            (points.head, points.last)
          }

        // linear interpolation, solve for the x intercept given y (val), slope (dy/dx), and starting point (right)
        val dx = (right.time - left.time).toDouble
        val dy = (right.offset - left.offset).toDouble
        val Px = (right.time).toDouble
        val Dy = (right.offset - offset).toDouble

        Prediction(Px - Dy * dx / dy)
      }

      points.toList match {
        // if last point is same as looked up group offset then lag is zero
        case p if p.nonEmpty && offset == p.last.offset => LagIsZero
        case p if p.length < 2 => TooFewPoints
        case _ => estimate()

      }
    }

    /**
     * Return the most recently added `Point`.  Returns either an error message, or the `Point`.
     */
    def mostRecentPoint(): Either[String, Point] = {
      if (points.isEmpty) Left("No data in table")
      else Right(points.last)
    }
  }

  object Table {
    def apply(limit: Int): Table = Table(limit, mutable.Queue[Point]())

    sealed trait Result
    case object TooFewPoints extends Result
    case object LagIsZero extends Result
    final case class Prediction(time: Double) extends Result
  }
}
