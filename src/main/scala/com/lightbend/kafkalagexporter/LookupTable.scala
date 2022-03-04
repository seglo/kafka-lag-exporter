/*
 * Copyright (C) 2019-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import scala.collection.mutable
import com.redis._
import util.control.Breaks._
import org.slf4j.Logger

object LookupTable {
  import Domain._
  case class Table(tp: TopicPartition, limit: Int, points: mutable.Queue[Point]) {
    import Table._
    val key = "kafka-lag-exporter:" + tp.topic + ":" + tp.partition

    def addPoint(point: Point, redisClient: RedisClient): Unit = mostRecentPoint(redisClient) match {
      // new point is out of order
      case Right(mrp) if mrp.time >= point.time =>
      // new point is not part of a monotonically increasing set
      case Right(mrp) if mrp.offset > point.offset =>
      // compress flat lines to a single segment
      case Right(mrp) if mrp.offset == point.offset =>
        prune(redisClient)
        redisClient.zremrangebyscore(key = key, start = point.offset, end = point.offset)
        redisClient.zadd(key, point.offset.toDouble, point.time)
      // the table is empty or we filtered thru previous cases on the most recent point
      case _ =>
        // dequeue oldest point if we've hit the limit (sliding window)
        prune(redisClient)
        redisClient.zadd(key, point.offset.toDouble, point.time)
    }

    def mostRecentPoint(redisClient: RedisClient): Either[String, Point] = {
      val r = redisClient.zrangeWithScore(key = key, start = 0, end = 0, sortAs = RedisClient.DESC).get
      if (r.isEmpty) Left("No data in redis")
      else Right(new Point(r.head._2.toLong, r.head._1.toLong))
    }

    def oldestPoint(redisClient: RedisClient): Either[String, Point] = {
      val r = redisClient.zrangeWithScore(key = key, start = 0, end = 0, sortAs = RedisClient.ASC).get
      if (r.isEmpty) Left("No data in redis")
      else Right(new Point(r.head._2.toLong, r.head._1.toLong))
    }

    def prune(redisClient: RedisClient) {
      while (length(redisClient) >= limit) removeOldestPoint(redisClient)
    }

    def length(redisClient: RedisClient): Long = {
      redisClient.zcount(key).get
    }

    def removeOldestPoint(redisClient: RedisClient) {
      redisClient.zremrangebyrank(key, 0, 0)
    }

    def dump(redisClient: RedisClient): List[Point] = {
      var list: List[Point] = List()
      redisClient.zrangeWithScore(key).get.foreach{
        case (timeString, offsetDouble) =>
          breakable {
            var time: Long = 0
            var offset: Long = 0
            try { time = timeString.toLong } catch { case _: Exception => break}
            try { offset = offsetDouble.toLong } catch { case _: Exception => break}
            list = (new Point(offset.toLong, time) :: list)
          }
        case _ =>
      }
      return list
    }

    def lookup(offset: Long, redisClient: RedisClient, log: Logger): Result = {
      def estimate(): Result = {
        var extrapolated = false

        // log.info(" Left Point (offset) : {}", redisClient.zrangebyscoreWithScore(key = key, min = Double.NegativeInfinity, max = offset.toDouble, limit = Some((0, 1): (Int, Int)), sortAs = RedisClient.DESC))
        // log.info(" Left Point (default): {}", redisClient.zrangeWithScore(key = key, start = 0, end = 0, sortAs = RedisClient.ASC))
        val left: Point = redisClient.zrangebyscoreWithScore(key = key, min = Double.NegativeInfinity, max = offset.toDouble, limit = Some((0, 1): (Int, Int)), sortAs = RedisClient.DESC) match {
          // unexpected situation where the redis result is None
          case None => return InvalidLeftPoint
          // find the Point that contains the offset
          case Some(List((time, offset))) if (extrapolated == false) => new Point(offset.toLong, time.toLong)
          // offset is not between any two points in the table
          // extrapolate given largest trendline we have available
          case _ =>
            extrapolated = true
            oldestPoint(redisClient) match {
              case Left(_) => return InvalidLeftPoint
              case Right(point) => point
            }
        }

        // log.info("Right Point (offset) : {}", redisClient.zrangebyscoreWithScore(key = key, min = offset.toDouble, max = Double.PositiveInfinity, limit = Some((0, 1): (Int, Int)), sortAs = RedisClient.ASC))
        // log.info("Right Point (default): {}", redisClient.zrangeWithScore(key = key, start = 0, end = 0, sortAs = RedisClient.DESC))
        val right = redisClient.zrangebyscoreWithScore(key = key, min = offset.toDouble, max = Double.PositiveInfinity, limit = Some((0, 1): (Int, Int)), sortAs = RedisClient.ASC) match {
          // unexpected situation where the redis result is None
          case None => return InvalidRightPoint
          // find the Point that contains the offset
          case Some(List((time, offset))) if (extrapolated == false) => new Point(offset.toLong, time.toLong)
          // offset is not between any two points in the table
          // extrapolate given largest trendline we have available
          case _ =>
            extrapolated = true
            mostRecentPoint(redisClient) match {
              case Left(_) => return InvalidRightPoint
              case Right(point) => point
            }
        }

        predict(offset, left, right, extrapolated, log)
      }

      val _ = mostRecentPoint(redisClient) match {
        case Right(mrp) => 
          if (offset == mrp.offset) return LagIsZero
        case _ =>
      }
      if (length(redisClient) < 2) return TooFewPoints
      else estimate()
    }

    def predict(offset: Long, left: Point, right: Point, extrapolated: Boolean = false, log: Logger): Result = {
        // linear interpolation, solve for the x intercept given y (val), slope (dy/dx), and starting point (right)
        val dx = (right.time - left.time).toDouble
        val dy = (right.offset - left.offset).toDouble
        val Px = (right.time).toDouble
        val Dy = (right.offset - offset).toDouble

        //log.info("predict | dx = {} - {} = {} | dy = {} - {} = {} | Px = {} | Dy = {} - {} = {} | Prediction = {}",
        //  // dx
        //  right.time.toString(), left.time.toString(), (right.time - left.time).toLong.toString(),
        //  // dy
        //  right.offset.toString(), left.offset.toString(), (right.offset - left.offset).toLong.toString(),
        //  // Px
        //  right.time.toString(),
        //  // Dy
        //  right.offset.toString(), offset.toString(), (right.offset - offset).toLong.toString(),
        //  // Final
        //  (Px - Dy * dx / dy).toLong.toString()
        //)

        Prediction(Px - Dy * dx / dy, extrapolated)
    }

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
        var extrapolated = false
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
            extrapolated = true
            (points.head, points.last)
          }

        // linear interpolation, solve for the x intercept given y (val), slope (dy/dx), and starting point (right)
        val dx = (right.time - left.time).toDouble
        val dy = (right.offset - left.offset).toDouble
        val Px = (right.time).toDouble
        val Dy = (right.offset - offset).toDouble

        Prediction(Px - Dy * dx / dy, extrapolated = extrapolated)
      }

      points.toList match {
        // if last point is same as looked up group offset then lag is zero
        case p if p.nonEmpty && offset == p.last.offset => LagIsZero
        case p if p.length < 2 => TooFewPoints
        case _ => estimate()

      }
    }

    /**
      * Return the size of the points table
      */
    def length(): Int = {
      points.length
    }

    /**
      * Return a dump of the points table
      */
    def dump(): List[Point] = {
      points.toList
    }

    /**
     * Return the most recently added `Point`.  Returns either an error message, or the `Point`.
     */
    def mostRecentPoint(): Either[String, Point] = {
      if (points.isEmpty) Left("No data in table")
      else Right(points.last)
    }

    /**
     * Return the oldest added `Point`.  Returns either an error message, or the `Point`.
     */
    def oldestPoint(): Either[String, Point] = {
      if (points.isEmpty) Left("No data in table")
      else Right(points.head)
    }
  }

  object Table {
    def apply(tp: TopicPartition, limit: Int): Table = Table(tp, limit, mutable.Queue[Point]())

    sealed trait Result
    case object TooFewPoints extends Result
    case object LagIsZero extends Result
    case object InvalidLeftPoint extends Result
    case object InvalidRightPoint extends Result
    final case class Prediction(time: Double, extrapolated: Boolean = false) extends Result
  }
}
