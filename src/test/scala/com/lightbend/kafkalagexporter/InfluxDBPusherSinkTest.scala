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
 import org.scalatest.time.{Seconds, Millis , Span}
 import sys.process._

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
    val database = "kafka_lag_exporter"
    val cmd = Seq("curl", "-G", s"$url/query", "--data-urlencode", s"db=$database", "--data-urlencode", s"q=$query")
    val output = cmd.!!
    return output
  }

  "InfluxDBPusherSinkImpl should" - {

    "create database" in { fixture =>
      val sink = InfluxDBPusherSink(new InfluxDBPusherSinkConfig("InfluxDBPusherSink", List("kafka_consumergroup_group_max_lag"), ConfigFactory.parseMap(mapAsJavaMap(fixture.properties))), Map("cluster" -> Map.empty))
      val port = fixture.port
      val url = s"http://localhost:$port"
      val query = "SHOW DATABASES"

      eventually (timeout(Span(5, Seconds)), interval(Span(500, Millis))) { doQuery(url, query) should include("kafka_lag_exporter") }
    }

    "report metrics which match the regex" in { fixture =>
      val sink = InfluxDBPusherSink(new InfluxDBPusherSinkConfig("InfluxDBPusherSink", List("kafka_consumergroup_group_max_lag"), ConfigFactory.parseMap(mapAsJavaMap(fixture.properties))), Map("cluster" -> Map.empty))
      sink.report(Metrics.GroupValueMessage(Metrics.MaxGroupOffsetLagMetric, "cluster_test", "group_test", 100))
      sink.report(Metrics.GroupValueMessage(Metrics.MaxGroupTimeLagMetric, "cluster_test", "group_test", 101))

      val port = fixture.port
      val url = s"http://localhost:$port"

      val whitelist_query = "SELECT * FROM kafka_consumergroup_group_max_lag"
      val blacklist_query = "SELECT * FROM kafka_consumergroup_group_max_lag_seconds"

      eventually (timeout(Span(5, Seconds)), interval(Span(500, Millis))) { doQuery(url, whitelist_query) should (include("cluster_test") and include("group_test") and include("100")) }
      eventually (timeout(Span(5, Seconds)), interval(Span(500, Millis))) { doQuery(url, blacklist_query) should (not include("cluster_test") and not include("group_test") and not include("101")) }
    }
  }
}
