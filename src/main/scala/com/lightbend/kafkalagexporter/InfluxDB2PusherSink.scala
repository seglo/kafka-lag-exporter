package com.lightbend.kafkalagexporter

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Keep, Source}

import com.influxdb.client.InfluxDBClientOptions
import com.influxdb.client.domain.WritePrecision
import com.influxdb.client.scala.{InfluxDBClientScala, InfluxDBClientScalaFactory}
import com.influxdb.client.write.Point

import com.lightbend.kafkalagexporter.EndpointSink.ClusterGlobalLabels
import com.lightbend.kafkalagexporter.MetricsSink.{MetricValue, RemoveMetric}

import com.typesafe.scalalogging.Logger

import okhttp3.OkHttpClient

import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClientBuilder

import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.function.{BiConsumer, Consumer}

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Try

object InfluxDB2PusherSink {

  def apply(
             sinkConfig: InfluxDB2PusherSinkConfig,
             clusterGlobalLabels: ClusterGlobalLabels
           ): MetricsSink = {
    Try(new InfluxDB2PusherSink(sinkConfig, clusterGlobalLabels))
      .fold(
        t => throw new IOException("Could not create Influx DB 2 Pusher Sink", t),
        sink => sink
      )
  }
}

class InfluxDB2PusherSink private (
                                   sinkConfig: InfluxDB2PusherSinkConfig,
                                   clusterGlobalLabels: ClusterGlobalLabels
                                 ) extends EndpointSink(clusterGlobalLabels) {



  implicit val system: ActorSystem = ActorSystem("InfluxDB2PusherSink")
  val logger: Logger = Logger("InfluxDB2PusherSink")
  private val influxDbUrl = sinkConfig.endpoint + ":" + sinkConfig.port
  val influxDB: InfluxDBClientScala = connect()
  createBucket()

  override def report(m: MetricValue): Unit = {
    if (sinkConfig.metricWhitelist.exists(m.definition.name.matches) && !m.value.isNaN && !m.value.isInfinite) {
      write(m)
    }
  }

  def write(m: MetricValue): Unit = {
    try {
      val point = buildPoint(m)
      writeAsync(point)
    } catch {
      case t: Throwable =>
        handlingFailure(t)
    }
  }

  def writeAsync(point: Point): Unit = {
    val sourcePoint = Source.single(point)
    val sinkPoint = influxDB.getWriteScalaApi.writePoint(bucket = Some(sinkConfig.bucket))
    val materializedPoint = sourcePoint.toMat(sinkPoint)(Keep.right)
    Await.result(materializedPoint.run(), Duration.Inf)
  }

  def buildPoint(m: MetricValue): Point = {
    val point = Point
      .measurement(m.definition.name)
    for (
      globalLabels <- clusterGlobalLabels.get(m.clusterName);
      (tagName, tagValue) <- globalLabels
    )
      {
        point.addTag(tagName, tagValue)
      }

    val fields = m.definition.labels zip m.labels

    fields.foreach { field => point.addTag(field._1, field._2) }

    point
      .addField("value", m.value)
      .time(Instant.now(), WritePrecision.NS)
  }

  override def remove(m: RemoveMetric): Unit = {
    if (sinkConfig.metricWhitelist.exists(m.definition.name.matches))
      logger.warn("Remove is not supported by InfluxDBPusherSink")
  }

  def connect():  InfluxDBClientScala = {
    val builder = new OkHttpClient.Builder()
      .readTimeout(30, TimeUnit.SECONDS)
      .writeTimeout(30, TimeUnit.SECONDS)
      .connectTimeout(30, TimeUnit.SECONDS)

    val options = InfluxDBClientOptions.builder()
      .url(influxDbUrl)
      .okHttpClient(builder)
      .authenticateToken(sinkConfig.token.toCharArray)
      .org(sinkConfig.orgName)
      .bucket(sinkConfig.bucket)
      .build()

     InfluxDBClientScalaFactory
      .create(options)
  }

   private def createBucket(): Unit = {
    val httpClient = HttpClientBuilder.create().build()
    val url = influxDbUrl + "/api/v2/buckets"

    val bucketCreateJson =
      s"""{
            "orgID": "${sinkConfig.orgId}",
            "name": "${sinkConfig.bucket}",
            "description": "Kafka Lag exporter bucket",
             "rp": "string",
             "retentionRules": [
                 {
                     "type": "expire",
                     "everySeconds": ${sinkConfig.retentionSeconds},
                     "shardGroupDurationSeconds": 0
                }
             ],
            "schemaType": "implicit"
         }"""

    val httpPost = new HttpPost(url)
    httpPost.setEntity(new StringEntity(bucketCreateJson))

    httpPost.addHeader("Content-Type", "application/json")
    httpPost.addHeader("Authorization", s"Token ${sinkConfig.token}")

    val response = httpClient.execute(httpPost)

    if (response.getStatusLine.getStatusCode == 201) {
      logger.info(s"Bucket ${sinkConfig.bucket} created successfully")
    }
    else if(response.getStatusLine.getStatusCode == 422) {
      logger.info(s"Bucket ${sinkConfig.bucket} already exist")
    }
    else {
      logger.error(s"Bucket ${sinkConfig.bucket} couldn't be created as ${response.getStatusLine.getReasonPhrase} ")
    }

  }


  def failQueryHandler(): Consumer[Throwable] = {
    (throwable: Throwable) => {
      handlingFailure(throwable)
    }
  }

  def createExceptionHandler(): BiConsumer[Iterable[Point], Throwable] = {
    (_: Iterable[Point], throwable: Throwable) => {
      handlingFailure(throwable)
    }
  }

  def handlingFailure(t: Throwable): Unit = {
    logger.error("Unrecoverable exception, will stop ", t)
    stop()
    throw t
  }

  override def stop(): Unit = {
    influxDB.close()
  }
}


