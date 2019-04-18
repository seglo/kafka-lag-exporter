package com.lightbend.kafka.kafkametricstools
import scala.collection.mutable.ListBuffer

object LookupTable {
  case class Point(offset: Long, time: Long)
  case class Table(limit: Int, var points: ListBuffer[Point]) {
    def addPoint(point: Point): Unit = {
      val last = points.length - 1

      if (last >= 0) {
        val lastPoint = points.last

        // new point is out of order
        if (lastPoint.time >= point.time) return
        // new point is not part of a monotonically increasing set
        else if (lastPoint.offset > point.offset) return
        // compress flat lines to a single segment
        // rather than run the table into a flat line, just move the right hand side out until we see variation again
        else if (lastPoint.offset == point.offset) {
          if (last - 1 >= 0 && points(last - 1).offset == point.offset) {
            points(last) = point
            return
          }
        }
      }

      points = point +: points.slice(0, limit - 1)
    }

    def lookup(offset: Long): Double = {
      val top = points.length - 1
      if (top < 1) {
        return Double.NaN
      }

      // search for cell that contains the given val
      // search failure results in index -1
      var under = top

      while (under >= 0 && points(under).offset <= offset) {
        under -= 1
      }

      var left: Point = null
      var right: Point = null

      if (under < 0 || under == top) {
        // val is not in table
        // extrapolate given largest trendline we have available
        left = points.head
        right = points(top)
      } else {
        // val is within table
        // interpolate within table cell
        left = points(under)
        right = points(under + 1)
      }

      // linear interpolation, solve for the x intercept given y (val), slope (dy/dx), and starting point (right)

      val dx = (right.time - left.time).toDouble
      val dy = (right.offset - left.offset).toDouble
      val Px = (right.time).toDouble
      val Dy = (right.offset - offset).toDouble

      Px - Dy*dx/dy
    }

    def lastOffset(): Either[String, Point] = {
      if (points.isEmpty) Left("No data in table")
      else Right(points.head)
    }
  }

  object Table {
    def apply(limit: Int): Table = Table(limit, ListBuffer[Point]())
  }
}
