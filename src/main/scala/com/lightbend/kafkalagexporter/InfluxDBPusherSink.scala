/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2022 Sean Glover <https://seanglover.com>
 */

package com.lightbend.kafkalagexporter

import com.lightbend.kafkalagexporter.MetricsSink._
import com.lightbend.kafkalagexporter.EndpointSink.ClusterGlobalLabels
import org.influxdb.{InfluxDB, InfluxDBFactory, BatchOptions}
import org.influxdb.InfluxDB.ConsistencyLevel
import org.influxdb.dto.{Query, Point, BatchPoints}
import java.io.IOException
import java.util.function.Consumer
import java.util.function.BiConsumer
import org.influxdb.dto.QueryResult
import java.lang.Iterable
import com.typesafe.scalalogging.Logger

import scala.util.Try

object InfluxDBPusherSink {
  def apply(
      sinkConfig: InfluxDBPusherSinkConfig,
      clusterGlobalLabels: ClusterGlobalLabels
  ): MetricsSink = {
    Try(new InfluxDBPusherSink(sinkConfig, clusterGlobalLabels))
      .fold(
        t => throw new IOException("Could not create Influx DB Pusher Sink", t),
        sink => sink
      )
  }
}

class InfluxDBPusherSink private (
    sinkConfig: InfluxDBPusherSinkConfig,
    clusterGlobalLabels: ClusterGlobalLabels
) extends EndpointSink(clusterGlobalLabels) {

  val logger = Logger("InfluxDBPusherSink")
  val influxDB = connect()
  createDatabase()
  enableBatching()

  override def report(m: MetricValue): Unit = {
    if (sinkConfig.metricWhitelist.exists(m.definition.name.matches)) {
      if (!m.value.isNaN && !m.value.isInfinite) {
        write(m)
      }
    }
  }

  def write(m: MetricValue): Unit = {
    try {
      val point = buildPoint(m)
      if (sinkConfig.async)
        writeAsync(point)
      else
        writeSync(point)
    } catch {
      case t: Throwable =>
        handlingFailure(t)
    }
  }

  def writeAsync(point: Point): Unit = {
    influxDB.write(point)
  }

  def writeSync(point: Point): Unit = {
    val batchPoints = BatchPoints
      .database(sinkConfig.database)
      .consistency(ConsistencyLevel.ALL)
      .build()

    batchPoints.point(point)
    influxDB.write(batchPoints)
  }

  def buildPoint(m: MetricValue): Point = {
    val point = Point.measurement(m.definition.name)
    for (
      globalLabels <- clusterGlobalLabels.get(m.clusterName);
      (tagName, tagValue) <- globalLabels
    )
      point.tag(tagName, tagValue)
    val fields = m.definition.labels zip m.labels
    fields.foreach { field => point.tag(field._1, field._2) }
    point.addField("value", m.value)
    point.build()
  }

  override def remove(m: RemoveMetric): Unit = {
    if (sinkConfig.metricWhitelist.exists(m.definition.name.matches))
      logger.warn("Remove is not supported by InfluxDBPusherSink")
  }

  def enableBatching(): Unit = {
    if (sinkConfig.async) {
      influxDB.setDatabase(sinkConfig.database)
      influxDB.enableBatch(
        BatchOptions.DEFAULTS.exceptionHandler(createExceptionHandler())
      )
    }
  }

  def connect(): InfluxDB = {
    val url = sinkConfig.endpoint + ":" + sinkConfig.port
    if (!sinkConfig.username.isEmpty)
      InfluxDBFactory.connect(url, sinkConfig.username, sinkConfig.password)
    else InfluxDBFactory.connect(url)
  }

  def createDatabase() = {
    influxDB.query(
      new Query("CREATE DATABASE " + sinkConfig.database, sinkConfig.database),
      successQueryHandler(),
      failQueryHandler()
    )
  }

  def successQueryHandler(): Consumer[QueryResult] = {
    new Consumer[QueryResult] {
      override def accept(result: QueryResult): Unit = {
        logger.info(result.toString())
      }
    }
  }

  def failQueryHandler(): Consumer[Throwable] = {
    new Consumer[Throwable] {
      override def accept(throwable: Throwable): Unit = {
        handlingFailure(throwable)
      }
    }
  }

  def createExceptionHandler(): BiConsumer[Iterable[Point], Throwable] = {
    new BiConsumer[Iterable[Point], Throwable] {
      override def accept(
          failedPoints: Iterable[Point],
          throwable: Throwable
      ): Unit = {
        handlingFailure(throwable)
      }
    }
  }

  def handlingFailure(t: Throwable): Unit = {
    logger.error("Unrecoverable exception, will stop ", t)
    stop()
    throw t
  }

  override def stop(): Unit = {
    if (influxDB.isBatchEnabled()) {
      influxDB.disableBatch()
    }
    influxDB.close()
  }
}
