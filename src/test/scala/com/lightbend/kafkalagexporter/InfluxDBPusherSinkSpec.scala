/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2022 Sean Glover <https://seanglover.com>
 */

package com.lightbend.kafkalagexporter

import java.net.URL

import com.typesafe.config.ConfigFactory
import org.influxdb.dto.Query
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.freespec.FixtureAnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{TryValues, _}
import org.testcontainers.containers.InfluxDBContainer
import org.testcontainers.utility.DockerImageName

import scala.jdk.CollectionConverters._

class InfluxDBPusherSinkSpec
    extends FixtureAnyFreeSpec
    with Matchers
    with BeforeAndAfterAll
    with TryValues
    with Eventually
    with IntegrationPatience {

  private val image = DockerImageName.parse("influxdb").withTag("1.4.3")
  private val adminPassword = "shh"
  private val container = {
    val c = new InfluxDBContainer(image)
    c.withDatabase("kafka_lag_exporter")
    c.withAdminPassword(adminPassword)
    c
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    container.start()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    container.stop()
  }

  case class Fixture(properties: Map[String, Any], port: Int)
  type FixtureParam = Fixture

  override def withFixture(test: OneArgTest): Outcome = {
    val url = new URL(container.getUrl)
    val port: Int = url.getPort
    val endpoint: String = s"${url.getProtocol}://${url.getHost}"
    val properties: Map[String, Any] = Map(
      "reporters.influxdb.endpoint" -> endpoint,
      "reporters.influxdb.port" -> port,
      "reporters.influxdb.username" -> "admin",
      "reporters.influxdb.password" -> adminPassword
    )
    test(Fixture(properties, port))
  }

  def doQuery(query: String): String = {
    val db = container.getNewInfluxDB
    val database = "kafka_lag_exporter"
    val response = db.query(new Query(query, database))

    response.getResults.asScala.mkString("\n")
  }

  "InfluxDBPusherSinkImpl should" - {

    "create database" in { fixture =>
      val _ = InfluxDBPusherSink(
        new InfluxDBPusherSinkConfig(
          "InfluxDBPusherSink",
          List("kafka_consumergroup_group_max_lag"),
          ConfigFactory.parseMap(fixture.properties.asJava)
        ),
        Map("cluster" -> Map.empty)
      )
      val query = "SHOW DATABASES"

      eventually { doQuery(query) should include("kafka_lag_exporter") }
    }

    "report metrics which match the regex" in { fixture =>
      val sink = InfluxDBPusherSink(
        new InfluxDBPusherSinkConfig(
          "InfluxDBPusherSink",
          List("kafka_consumergroup_group_max_lag"),
          ConfigFactory.parseMap(fixture.properties.asJava)
        ),
        Map("cluster" -> Map.empty)
      )
      sink.report(
        Metrics.GroupValueMessage(
          Metrics.MaxGroupOffsetLagMetric,
          "cluster_test",
          "group_test",
          100
        )
      )
      sink.report(
        Metrics.GroupValueMessage(
          Metrics.MaxGroupTimeLagMetric,
          "cluster_test",
          "group_test",
          101
        )
      )

      val whitelist_query = "SELECT * FROM kafka_consumergroup_group_max_lag"
      val blacklist_query =
        "SELECT * FROM kafka_consumergroup_group_max_lag_seconds"

      eventually {
        doQuery(whitelist_query) should (include("cluster_test") and include(
          "group_test"
        ) and include("100"))
      }
      eventually {
        doQuery(
          blacklist_query
        ) should (not include ("cluster_test") and not include ("group_test") and not include ("101"))
      }
    }

    "should get the correct global label names and values as tags" in {
      fixture =>
        val clustersGlobalValuesMap = Map(
          "cluster_test" -> Map("environment" -> "integration")
        )

        val sink = InfluxDBPusherSink(
          new InfluxDBPusherSinkConfig(
            "InfluxDBPusherSink",
            List("kafka_consumergroup_group_max_lag"),
            ConfigFactory.parseMap(fixture.properties.asJava)
          ),
          clustersGlobalValuesMap
        )
        sink.report(
          Metrics.GroupValueMessage(
            Metrics.MaxGroupOffsetLagMetric,
            "cluster_test",
            "group_test",
            100
          )
        )

        val tagQuery =
          "SELECT * FROM kafka_consumergroup_group_max_lag where environment='integration'"

        eventually {
          doQuery(tagQuery) should (include("cluster_test") and include(
            "group_test"
          ) and include("100") and include("environment") and include(
            "integration"
          ))
        }
    }
  }
}
