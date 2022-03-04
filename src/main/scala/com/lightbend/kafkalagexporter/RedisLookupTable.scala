/*
 * Copyright (C) 2019-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import com.redis._
import util.control.Breaks._
//import org.slf4j.Logger

object RedisLookupTable {
  import Domain._

  case class RedisTable(tp: TopicPartition, limit: Int) {
    import RedisTable._
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

    def lookup(offset: Long, redisClient: RedisClient/*, log: Logger*/): Result = {
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

        predict(offset, left, right, extrapolated/*, log*/)
      }

      val _ = mostRecentPoint(redisClient) match {
        case Right(mrp) => 
          if (offset == mrp.offset) return LagIsZero
        case _ =>
      }
      if (length(redisClient) < 2) return TooFewPoints
      else estimate()
    }

    def predict(offset: Long, left: Point, right: Point, extrapolated: Boolean = false/*, log: Option[Logger] = None*/): Result = {
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

  }

  object RedisTable {
    def apply(tp: TopicPartition, limit: Int): RedisTable = RedisTable(tp, limit)

    sealed trait Result
    case object TooFewPoints extends Result
    case object LagIsZero extends Result
    case object InvalidLeftPoint extends Result
    case object InvalidRightPoint extends Result
    final case class Prediction(time: Double, extrapolated: Boolean = false) extends Result
  }
}
