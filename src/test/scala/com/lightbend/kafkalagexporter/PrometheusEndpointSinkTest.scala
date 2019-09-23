/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import java.net.ServerSocket

import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.HTTPServer
import org.scalatest._

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

class PrometheusEndpointSinkTest extends fixture.FreeSpec with Matchers {

  case class Fixture(server: HTTPServer, registry: CollectorRegistry)
  type FixtureParam = Fixture

  override def withFixture(test: OneArgTest): Outcome = {
    val httpServer =
      Try(new ServerSocket(0)) match {
        case Success(socket) =>
          val freePort = socket.getLocalPort
          socket.close()
          new HTTPServer(freePort)
        case Failure(exception) => throw exception
      }
    val registry = CollectorRegistry.defaultRegistry
    try test(Fixture(httpServer, registry))
    finally {
      registry.clear()
      httpServer.stop()
    }
  }

  "PrometheusEndpointSinkImpl should" - {

    "register only metrics which match the regex" in { fixture =>
      PrometheusEndpointSink(Metrics.definitions, List(".*max_lag.*"), Map("cluster" -> Map.empty), fixture.server, fixture.registry)
      val metricSamples = fixture.registry.metricFamilySamples().asScala.toSet

      metricSamples.map(_.name).intersect(Metrics.definitions.map(_.name).toSet) should contain theSameElementsAs
        Set("kafka_consumergroup_group_max_lag", "kafka_consumergroup_group_max_lag_seconds")
    }

    "append global labels to metric labels" in { fixture =>
      val groupLabel = Map(
        "cluster" -> Map(
          "environment" -> "dev",
          "org" -> "organization",
        ),
        "cluster2" -> Map(
          "environment" -> "prod",
          "org" -> "organization2",
          "location" -> "canada"
        )
      )
      val sink = PrometheusEndpointSink(Metrics.definitions, List(".*"), groupLabel, fixture.server, fixture.registry)
      sink.report(Metrics.GroupValueMessage(Metrics.MaxGroupTimeLagMetric, "cluster", "group", 1))

      val metricSamples = fixture.registry.metricFamilySamples().asScala.toList
      val maxGroupTimeLagMetricSamples = metricSamples.filter(_.name.equals(Metrics.MaxGroupTimeLagMetric.name)).flatMap(_.samples.asScala)

      maxGroupTimeLagMetricSamples should have length 1
      val labels = maxGroupTimeLagMetricSamples.flatMap(_.labelNames.asScala)
      val labelValues = maxGroupTimeLagMetricSamples.flatMap(_.labelValues.asScala)
      (labels zip labelValues).toMap should contain theSameElementsAs
        Map(
          "environment" ->"dev",
          "org" -> "organization",
          "cluster_name" -> "cluster",
          "group" -> "group",
        )

      sink.remove(Metrics.GroupRemoveMetricMessage(Metrics.MaxGroupTimeLagMetric, "cluster", "group"))

      val metricSamplesAfterRemoval = fixture.registry.metricFamilySamples().asScala.toList
      val maxGroupTimeLagMetricSamplesAfterRemoval = metricSamplesAfterRemoval.filter(_.name.equals(Metrics.MaxGroupTimeLagMetric.name)).flatMap(_.samples.asScala)


      maxGroupTimeLagMetricSamplesAfterRemoval should have length 0
    }

    "report only metrics which match the regex" in { fixture =>
      val sink = PrometheusEndpointSink(Metrics.definitions, List("kafka_consumergroup_group_max_lag"), Map("cluster" -> Map.empty),
        fixture.server, fixture.registry)
      sink.report(Metrics.GroupValueMessage(Metrics.MaxGroupOffsetLagMetric, "cluster", "group", 100))
      sink.report(Metrics.GroupValueMessage(Metrics.MaxGroupTimeLagMetric, "cluster", "group", 1))
      val labels = Array[String]("cluster_name", "group")
      val labelVals = Array[String]("cluster", "group")
      fixture.registry.getSampleValue("kafka_consumergroup_group_max_lag", labels, labelVals) should be (100)
      val metricSamples = fixture.registry.metricFamilySamples().asScala.toSet
      metricSamples.map(_.name) should not contain "kafka_consumergroup_group_max_lag_seconds"
    }

    "remove only metrics which match the regex" in { fixture =>
      val sink = PrometheusEndpointSink(Metrics.definitions, List("kafka_consumergroup_group_max_lag"), Map("cluster" -> Map.empty),
        fixture.server, fixture.registry)
      sink.report(Metrics.GroupValueMessage(Metrics.MaxGroupOffsetLagMetric, "cluster", "group", 100))
      sink.remove(Metrics.GroupRemoveMetricMessage(Metrics.MaxGroupOffsetLagMetric, "cluster", "group"))
      sink.remove(Metrics.GroupRemoveMetricMessage(Metrics.MaxGroupTimeLagMetric, "cluster", "group"))
      val labels = Array[String]("cluster_name", "group")
      val labelVals = Array[String]("cluster", "group")
      fixture.registry.getSampleValue("kafka_consumergroup_group_max_lag", labels, labelVals) should be (null)
    }

  }

}
