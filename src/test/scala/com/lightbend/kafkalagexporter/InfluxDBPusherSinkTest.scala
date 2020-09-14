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

  "InfluxDBPusherSinkImpl should" - {

    "create database" in { fixture =>
      val sink = InfluxDBPusherSink(new InfluxDBPusherSinkConfig("InfluxDBPusherSink", List("kafka_consumergroup_group_max_lag"), ConfigFactory.parseMap(mapAsJavaMap(fixture.properties))), Map("cluster" -> Map.empty))
      Thread.sleep(1000)
      val port = fixture.port
      val url = s"http://localhost:$port"
      val query = "SHOW DATABASES"
      val cmd = Seq("curl", "-G", s"$url/query", "--data-urlencode", s"q=$query")
      val output = cmd.!!
      output should include("kafka_lag_exporter")
    }

    "report metrics which match the regex" in { fixture =>
      val sink = InfluxDBPusherSink(new InfluxDBPusherSinkConfig("InfluxDBPusherSink", List("kafka_consumergroup_group_max_lag"), ConfigFactory.parseMap(mapAsJavaMap(fixture.properties))), Map("cluster" -> Map.empty))
      sink.report(Metrics.GroupValueMessage(Metrics.MaxGroupOffsetLagMetric, "cluster_test", "group_test", 100))
      sink.report(Metrics.GroupValueMessage(Metrics.MaxGroupTimeLagMetric, "cluster_test", "group_test", 101))

      Thread.sleep(5000)

      val port = fixture.port
      val url = s"http://localhost:$port"
      val database = "kafka_lag_exporter"
      val whitelist_query = "SELECT * FROM kafka_consumergroup_group_max_lag"
      val blacklist_query = "SELECT * FROM kafka_consumergroup_group_max_lag_seconds"
      var cmd = Seq("curl", "-G", s"$url/query", "--data-urlencode", s"db=$database", "--data-urlencode", s"q=$whitelist_query")
      var output = cmd.!!
      output should (include("cluster_test") and include("group_test") and include("100"))
      cmd = Seq("curl", "-G", s"$url/query", "--data-urlencode", s"db=$database", "--data-urlencode", s"q=$blacklist_query")
      output = cmd.!!
      output should (not include("cluster_test") and not include("group_test") and not include("101"))
    }
  }
}
