/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2022 Sean Glover <https://seanglover.com>
 */

package com.lightbend.kafkalagexporter

import com.lightbend.kafkalagexporter.LookupTable.LookupResult.Prediction
import com.lightbend.kafkalagexporter.LookupTable.{
  AddPointResult,
  LookupResult,
  Point
}

import java.time.Clock
import com.lightbend.kafkalagexporter.LookupTableConfig.{
  MemoryTableConfig,
  RedisTableConfig
}
import com.redis.RedisClient

import scala.collection.mutable
import scala.util.control._

sealed trait LookupTable {

  /** Add the `Point` to the table. Note: `point.time` should be considered the
    * "current" timestamp.
    */
  def addPoint(point: Point): AddPointResult
  def lookup(offset: Long): LookupResult

  /** Return the size of the lookup table.
    */
  def length: Long

  /** Is this point the same reading as the last 2 most recent points?
    */
  def isFlat(point: Point): Boolean

  // TOOD: add `pointAtIndex` to lookup by points by index

  /** linear interpolation, solve for the x intercept given y (val), slope
    * (dy/dx), and starting point (right)
    */
  final def predict(offset: Long, left: Point, right: Point): LookupResult = {
    val dx = (right.time - left.time).toDouble
    val dy = (right.offset - left.offset).toDouble
    val Px = right.time.toDouble
    val Dy = (right.offset - offset).toDouble
    Prediction(Px - Dy * dx / dy)
  }
}

object LookupTable {
  import Domain._
  import LookupResult._
  import AddPointResult._

  final case class Point(offset: Long, time: Long)

  sealed trait LookupResult
  sealed trait AddPointResult

  object LookupResult {
    case object TooFewPoints extends LookupResult
    case object LagIsZero extends LookupResult
    final case class Prediction(time: Double) extends LookupResult
  }

  object AddPointResult {
    case object OutOfOrder extends AddPointResult
    case object NonMonotonic extends AddPointResult
    case object Updated extends AddPointResult
    case object Inserted extends AddPointResult
  }

  def apply(
      clusterName: String,
      topicPartition: TopicPartition,
      config: LookupTableConfig
  ): LookupTable = config match {
    case c: RedisTableConfig  => RedisTable(clusterName, topicPartition, c)
    case c: MemoryTableConfig => MemoryTable(c)
  }

  case class RedisTable(
      clusterName: String,
      tp: TopicPartition,
      config: RedisTableConfig,
      private[kafkalagexporter] val clock: Clock = Clock.systemUTC()
  ) extends LookupTable {
    import config._

    val pointsKey = List(prefix, clusterName, tp.topic, tp.partition, "points")
      .mkString(separator)
    val client = config.client

    /** Add the `Point` to the table.
      */
    def addPoint(point: Point): AddPointResult = {
      mostRecentPoint() match {
        // new point is out of order
        case Right(mrp) if mrp.time >= point.time => OutOfOrder
        // new point is not part of a monotonically increasing set
        case Right(mrp) if mrp.offset > point.offset => NonMonotonic
        // compress flat lines to a single segment
        case Right(_) if isFlat(point) =>
          removeExpiredPoints()
          // get first and last time for flat points
          val times = client
            .zrangebyscore(
              key = pointsKey,
              min = point.offset,
              max = point.offset,
              limit = Some((0, 2): (Int, Int))
            )
            .get
          // remove points with the same offset
          client.zremrangebyscore(
            key = pointsKey,
            start = point.offset,
            end = point.offset
          )
          // insert earliest + current points
          client.zadd(pointsKey, point.offset.toDouble, times.minBy(_.toLong))
          client.zadd(pointsKey, point.offset.toDouble, point.time)
          expireKeys()
          Updated
        case _ =>
          // dequeue oldest point if we've hit the limit
          removeExpiredPoints()
          client.zadd(pointsKey, point.offset.toDouble, point.time)
          expireKeys()
          Inserted
      }
    }

    /** Predict the timestamp of a provided offset using interpolation if in the
      * sliding window, or extrapolation if outside the sliding window.
      */
    def lookup(offset: Long): LookupResult = {
      def estimate(): LookupResult = {
        // Look in the sorted set for an exact point. It can happens by chance or during flattened range
        client zrangebyscore (key = pointsKey, min = offset.toDouble, max =
          offset.toDouble, limit = Some((0, 1): (Int, Int)), sortAs =
          RedisClient.DESC) match {
          // unexpected situation where the redis result is None
          case None => sys.error("Point not found!")
          case Some(points) if points.nonEmpty =>
            return Prediction(points.head.toDouble)
          case Some(_) => // Exact point not found, moving to range calculation
        }

        var left: Either[String, Point] = client.zrangebyscoreWithScore(
          key = pointsKey,
          min = 0,
          max = offset.toDouble,
          maxInclusive = false,
          limit = Some((0, 1): (Int, Int)),
          sortAs = RedisClient.DESC
        ) match {
          // unexpected situation where the redis result is None
          case None => sys.error("Point not found!")
          // find the left Point from the offset
          case Some(lefts) if lefts.nonEmpty =>
            Right(Point(lefts.head._2.toLong, lefts.head._1.toLong))
          // offset is not between any two points in the table
          case _ =>
            // extrapolated = true
            Left("Extrapolation required")
        }

        var right: Either[String, Point] = client.zrangebyscoreWithScore(
          key = pointsKey,
          min = offset.toDouble,
          minInclusive = false,
          max = Double.PositiveInfinity,
          limit = Some((0, 1): (Int, Int)),
          sortAs = RedisClient.ASC
        ) match {
          // unexpected situation where the redis result is None
          case None => sys.error("Point not found!")
          // find the right Point from the offset
          case Some(rights) if rights.nonEmpty || left.isLeft =>
            Right(Point(rights.head._2.toLong, rights.head._1.toLong))
          // offset is not between any two points in the table
          case _ =>
            // extrapolated = true
            Left("Extrapolation required")
        }

        // extrapolate given largest trendline we have available
        if (left.isLeft || right.isLeft) {
          left = oldestPoint()
          right = mostRecentPoint()
        }

        if (left.isRight && right.isRight) {
          predict(offset, left.right.get, right.right.get)
        } else {
          TooFewPoints
        }
      }

      mostRecentPoint() match {
        case Right(mrp) if mrp.offset == offset => LagIsZero
        case _ if length < 2                    => TooFewPoints
        case _                                  => estimate()
      }
    }

    /** Expire keys in Redis with the configured TTL
      */
    def expireKeys(): Unit = {
      client.expire(pointsKey, expiration.toSeconds.toInt)
    }

    /** Remove points that are older than the configured retention
      */
    def removeExpiredPoints(): Unit = {
      val currentTimestamp: Long = clock.instant().toEpochMilli
      val loop = new Breaks
      loop.breakable {
        for (_ <- (0: Long) until length) {
          oldestPoint() match {
            case Left(_) => loop.break() // No Data
            case Right(p) =>
              if (currentTimestamp - retention.toMillis > p.time)
                removeOldestPoint()
              else loop.break()
          }
        }
      }
    }

    /** Remove the oldest point
      */
    def removeOldestPoint(): Unit = {
      client.zremrangebyrank(pointsKey, 0, 0)
    }

    /** Return the oldest `Point`. Returns either an error message, or the
      * `Point`.
      */
    def oldestPoint(
        start: Int = 0,
        end: Int = 0
    ): Either[String, Point] = {
      val r = client
        .zrangeWithScore(
          key = pointsKey,
          start = start,
          end = end,
          sortAs = RedisClient.ASC
        )
        .get
      if (r.isEmpty) Left("No data in redis")
      else Right(Point(r.head._2.toLong, r.head._1.toLong))
    }

    /** Return the size of the lookup table.
      */
    override def length: Long = client.zcount(pointsKey).getOrElse(0)

    /** Return the most recently added `Point`. Returns either an error message,
      * or the `Point`.
      */
    def mostRecentPoint(
        start: Int = 0,
        end: Int = 0
    ): Either[String, Point] = {
      val r = client
        .zrangeWithScore(
          key = pointsKey,
          start = start,
          end = end,
          sortAs = RedisClient.DESC
        )
        .get
      if (r.isEmpty) Left("No data in redis")
      else Right(Point(r.head._2.toLong, r.head._1.toLong))
    }

    /** Is this point the same reading as the last 2 most recent points?
      */
    override def isFlat(point: Point): Boolean = {
      length > 1 && mostRecentPoint().toOption.exists(
        _.offset == point.offset
      ) &&
      mostRecentPoint(1, 1).toOption.exists(_.offset == point.offset)
    }
  }

  case class MemoryTable(config: MemoryTableConfig) extends LookupTable {
    val limit = config.size
    val points = mutable.Queue[Point]()

    /** Add the `Point` to the table.
      */
    def addPoint(point: Point): AddPointResult = mostRecentPoint() match {
      // new point is out of order
      case Right(mrp) if mrp.time >= point.time => OutOfOrder
      // new point is not part of a monotonically increasing set
      case Right(mrp) if mrp.offset > point.offset => NonMonotonic
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
      // compress flat lines to a single segment
      case Right(_) if isFlat(point) =>
        // update the most recent point
        points(points.length - 1) = point
        Updated
      // the table is empty or we filtered thru previous cases on the most recent point
      case _ =>
        // dequeue oldest point if we've hit the limit (sliding window)
        if (points.length == limit) points.dequeue()
        points.enqueue(point)
        Inserted
    }

    /** Predict the timestamp of a provided offset using interpolation if in the
      * sliding window, or extrapolation if outside the sliding window.
      */
    def lookup(offset: Long): LookupResult = {
      def estimate(): LookupResult = {
        // search for two cells that contains the given offset
        val (left, right) = points.reverseIterator
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

        predict(offset, left, right)
      }

      points.toList match {
        // if last point is same as looked up group offset then lag is zero
        case p if p.nonEmpty && offset == p.last.offset => LagIsZero
        case p if p.length < 2                          => TooFewPoints
        case _                                          => estimate()

      }
    }

    /** Return the most recently added `Point`. Returns either an error message,
      * or the `Point`.
      */
    def mostRecentPoint(): Either[String, Point] = {
      if (points.isEmpty) Left("No data in table")
      else Right(points.last)
    }

    /** Return the size of the lookup table.
      */
    override def length: Long = points.length

    override def isFlat(point: Point): Boolean =
      length > 1 && mostRecentPoint().toOption.exists(
        _.offset == point.offset
      ) &&
        points(points.length - 2).offset == point.offset
  }
}
