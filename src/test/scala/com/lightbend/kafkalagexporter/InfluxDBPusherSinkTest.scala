/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

 package com.lightbend.kafkalagexporter

 import org.scalatest._
 import com.typesafe.config.ConfigFactory
 import scala.collection.JavaConversions.mapAsJavaMap
 import com.github.fsanaulla.core.testing.configurations.InfluxUDPConf
 import com.github.fsanaulla.scalatest.embedinflux.EmbeddedInfluxDB
 import org.scalatest.concurrent.{Eventually, IntegrationPatience}
 import org.scalatest.{Matchers, TryValues}
 import akka.http.scaladsl.unmarshalling.Unmarshal
 import java.net.URLEncoder
 import akka.actor.typed.ActorSystem
 import akka.actor.typed.scaladsl.Behaviors
 import akka.http.scaladsl.Http
 import akka.http.scaladsl.model._

 import scala.concurrent.Await
 import scala.concurrent.duration._

class InfluxDBPusherSinkTest extends fixture.FreeSpec with Matchers
    with EmbeddedInfluxDB
    with InfluxUDPConf
    with TryValues
    with Eventually
    with IntegrationPatience {

  case class Fixture(properties: Map[String, Any], port: Int)
  type FixtureParam = Fixture

  override def withFixture(test: OneArgTest): Outcome = {
    val port: Int = 8086
    val properties: Map[String, Any] = Map(
      "reporters.influxDB.endpoint" -> "http://localhost",
      "reporters.influxDB.port" -> port
    )
    test(Fixture(properties, port))
  }

  def doQuery(url: String, query: String): String = {
    implicit val system = ActorSystem(Behaviors.empty, "SingleRequest")
    implicit val executionContext = system.executionContext

    val database = "kafka_lag_exporter"
    val request = HttpRequest(uri = s"$url/query?db=$database&q=$query")

    val response: HttpResponse = Await.result(Http(system).singleRequest(request), Duration(10, "seconds"))
    val body: String = Await.result(Unmarshal(response.entity).to[String], Duration(10, "seconds"))

    return body
  }

  "InfluxDBPusherSinkImpl should" - {

    "create database" in { fixture =>
      val sink = InfluxDBPusherSink(new InfluxDBPusherSinkConfig("InfluxDBPusherSink", List("kafka_consumergroup_group_max_lag"), ConfigFactory.parseMap(mapAsJavaMap(fixture.properties))), Map("cluster" -> Map.empty))
      val port = fixture.port
      val url = s"http://localhost:$port"
      val query = URLEncoder.encode("SHOW DATABASES", "UTF-8");

      eventually { doQuery(url, query) should include("kafka_lag_exporter") }
    }

    "report metrics which match the regex" in { fixture =>
      val sink = InfluxDBPusherSink(new InfluxDBPusherSinkConfig("InfluxDBPusherSink", List("kafka_consumergroup_group_max_lag"), ConfigFactory.parseMap(mapAsJavaMap(fixture.properties))), Map("cluster" -> Map.empty))
      sink.report(Metrics.GroupValueMessage(Metrics.MaxGroupOffsetLagMetric, "cluster_test", "group_test", 100))
      sink.report(Metrics.GroupValueMessage(Metrics.MaxGroupTimeLagMetric, "cluster_test", "group_test", 101))

      val port = fixture.port
      val url = s"http://localhost:$port"

      val whitelist_query = URLEncoder.encode("SELECT * FROM kafka_consumergroup_group_max_lag", "UTF-8");
      val blacklist_query = URLEncoder.encode("SELECT * FROM kafka_consumergroup_group_max_lag_seconds", "UTF-8")

      eventually { doQuery(url, whitelist_query) should (include("cluster_test") and include("group_test") and include("100")) }
      eventually { doQuery(url, blacklist_query) should (not include("cluster_test") and not include("group_test") and not include("101")) }
    }
  }
}
