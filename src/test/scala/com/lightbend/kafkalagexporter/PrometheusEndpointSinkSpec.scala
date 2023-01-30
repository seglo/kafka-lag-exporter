/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2022 Sean Glover <https://seanglover.com>
 */

package com.lightbend.kafkalagexporter

import java.net.ServerSocket
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.HTTPServer

import scala.util.{Failure, Success, Try}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.Outcome
import org.scalatest.freespec.FixtureAnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters.{EnumerationHasAsScala, ListHasAsScala}

class PrometheusEndpointSinkSpec extends FixtureAnyFreeSpec with Matchers {

  case class Fixture(
      server: HTTPServer,
      registry: CollectorRegistry,
      config: Config
  )
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
    val config = ConfigFactory.load()
    try test(Fixture(httpServer, registry, config))
    finally {
      registry.clear()
      httpServer.stop()
    }
  }

  "PrometheusEndpointSinkImpl should" - {

    "register only metrics which match the regex" in { fixture =>
      PrometheusEndpointSink(
        new PrometheusEndpointSinkConfig(
          "PrometheusEndpointSink",
          List(".*max_lag.*"),
          fixture.config
        ),
        Metrics.definitions,
        Map("cluster" -> Map.empty),
        fixture.server,
        fixture.registry
      )
      val metricSamples = fixture.registry.metricFamilySamples().asScala.toSet

      metricSamples
        .map(_.name)
        .intersect(
          Metrics.definitions.map(_.name).toSet
        ) should contain theSameElementsAs
        Set(
          "kafka_consumergroup_group_max_lag",
          "kafka_consumergroup_group_max_lag_seconds"
        )
    }

    "append global labels to metric labels" in { fixture =>
      val groupLabel = Map(
        "cluster" -> Map(
          "environment" -> "dev",
          "org" -> "organization"
        ),
        "cluster2" -> Map(
          "environment" -> "prod",
          "org" -> "organization2",
          "location" -> "canada"
        ),
        "cluster3" -> Map.empty[String, String]
      )
      val sink = PrometheusEndpointSink(
        new PrometheusEndpointSinkConfig(
          "PrometheusEndpointSink",
          List(".*"),
          fixture.config
        ),
        Metrics.definitions,
        groupLabel,
        fixture.server,
        fixture.registry
      )
      sink.report(
        Metrics.GroupValueMessage(
          Metrics.MaxGroupTimeLagMetric,
          "cluster",
          "group",
          1
        )
      )
      sink.report(
        Metrics.GroupValueMessage(
          Metrics.MaxGroupTimeLagMetric,
          "cluster2",
          "group",
          1
        )
      )
      sink.report(
        Metrics.GroupValueMessage(
          Metrics.MaxGroupTimeLagMetric,
          "cluster3",
          "group",
          1
        )
      )

      val metricSamples = fixture.registry.metricFamilySamples().asScala.toList
      val maxGroupTimeLagMetricSamples = metricSamples
        .filter(_.name.equals(Metrics.MaxGroupTimeLagMetric.name))
        .flatMap(_.samples.asScala)

      maxGroupTimeLagMetricSamples should have length 3
      val labelsNames1 = maxGroupTimeLagMetricSamples(0).labelNames.asScala
      val labelValues1 = maxGroupTimeLagMetricSamples(0).labelValues.asScala

      (labelsNames1 zip labelValues1).toMap should contain theSameElementsAs
        Map(
          "environment" -> "",
          "org" -> "",
          "cluster_name" -> "cluster3",
          "location" -> "",
          "group" -> "group"
        )

      val labelsNames2 = maxGroupTimeLagMetricSamples(1).labelNames.asScala
      val labelValues2 = maxGroupTimeLagMetricSamples(1).labelValues.asScala

      (labelsNames2 zip labelValues2).toMap should contain theSameElementsAs
        Map(
          "environment" -> "dev",
          "org" -> "organization",
          "cluster_name" -> "cluster",
          "location" -> "",
          "group" -> "group"
        )

      val labelsNames3 = maxGroupTimeLagMetricSamples(2).labelNames.asScala
      val labelValues3 = maxGroupTimeLagMetricSamples(2).labelValues.asScala

      (labelsNames3 zip labelValues3).toMap should contain theSameElementsAs
        Map(
          "environment" -> "prod",
          "org" -> "organization2",
          "cluster_name" -> "cluster2",
          "location" -> "canada",
          "group" -> "group"
        )

      sink.remove(
        Metrics.GroupRemoveMetricMessage(
          Metrics.MaxGroupTimeLagMetric,
          "cluster",
          "group"
        )
      )
      sink.remove(
        Metrics.GroupRemoveMetricMessage(
          Metrics.MaxGroupTimeLagMetric,
          "cluster2",
          "group"
        )
      )
      sink.remove(
        Metrics.GroupRemoveMetricMessage(
          Metrics.MaxGroupTimeLagMetric,
          "cluster3",
          "group"
        )
      )

      val metricSamplesAfterRemoval =
        fixture.registry.metricFamilySamples().asScala.toList
      val maxGroupTimeLagMetricSamplesAfterRemoval = metricSamplesAfterRemoval
        .filter(_.name.equals(Metrics.MaxGroupTimeLagMetric.name))
        .flatMap(_.samples.asScala)

      maxGroupTimeLagMetricSamplesAfterRemoval should have length 0
    }

    "should not append global labels to metric labels when global label is not specified for any cluster" in {
      fixture =>
        val groupLabel = Map(
          "cluster" -> Map.empty[String, String]
        )
        val sink = PrometheusEndpointSink(
          new PrometheusEndpointSinkConfig(
            "PrometheusEndpointSink",
            List(".*"),
            fixture.config
          ),
          Metrics.definitions,
          groupLabel,
          fixture.server,
          fixture.registry
        )
        sink.report(
          Metrics.GroupValueMessage(
            Metrics.MaxGroupTimeLagMetric,
            "cluster",
            "group",
            1
          )
        )

        val metricSamples =
          fixture.registry.metricFamilySamples().asScala.toList
        val maxGroupTimeLagMetricSamples = metricSamples
          .filter(_.name.equals(Metrics.MaxGroupTimeLagMetric.name))
          .flatMap(_.samples.asScala)

        maxGroupTimeLagMetricSamples should have length 1
        val labelsNames1 = maxGroupTimeLagMetricSamples(0).labelNames.asScala
        val labelValues1 = maxGroupTimeLagMetricSamples(0).labelValues.asScala

        (labelsNames1 zip labelValues1).toMap should contain theSameElementsAs
          Map(
            "cluster_name" -> "cluster",
            "group" -> "group"
          )

        sink.remove(
          Metrics.GroupRemoveMetricMessage(
            Metrics.MaxGroupTimeLagMetric,
            "cluster",
            "group"
          )
        )

        val metricSamplesAfterRemoval =
          fixture.registry.metricFamilySamples().asScala.toList
        val maxGroupTimeLagMetricSamplesAfterRemoval = metricSamplesAfterRemoval
          .filter(_.name.equals(Metrics.MaxGroupTimeLagMetric.name))
          .flatMap(_.samples.asScala)

        maxGroupTimeLagMetricSamplesAfterRemoval should have length 0
    }

    "report only metrics which match the regex" in { fixture =>
      val sink = PrometheusEndpointSink(
        new PrometheusEndpointSinkConfig(
          "PrometheusEndpointSink",
          List("kafka_consumergroup_group_max_lag"),
          fixture.config
        ),
        Metrics.definitions,
        Map("cluster" -> Map.empty),
        fixture.server,
        fixture.registry
      )
      sink.report(
        Metrics.GroupValueMessage(
          Metrics.MaxGroupOffsetLagMetric,
          "cluster",
          "group",
          100
        )
      )
      sink.report(
        Metrics.GroupValueMessage(
          Metrics.MaxGroupTimeLagMetric,
          "cluster",
          "group",
          1
        )
      )
      val labels = Array[String]("cluster_name", "group")
      val labelVals = Array[String]("cluster", "group")
      fixture.registry.getSampleValue(
        "kafka_consumergroup_group_max_lag",
        labels,
        labelVals
      ) should be(100)
      val metricSamples = fixture.registry.metricFamilySamples().asScala.toSet
      metricSamples.map(
        _.name
      ) should not contain "kafka_consumergroup_group_max_lag_seconds"
    }

    "remove only metrics which match the regex" in { fixture =>
      val sink = PrometheusEndpointSink(
        new PrometheusEndpointSinkConfig(
          "PrometheusEndpointSink",
          List("kafka_consumergroup_group_max_lag"),
          fixture.config
        ),
        Metrics.definitions,
        Map("cluster" -> Map.empty),
        fixture.server,
        fixture.registry
      )
      sink.report(
        Metrics.GroupValueMessage(
          Metrics.MaxGroupOffsetLagMetric,
          "cluster",
          "group",
          100
        )
      )
      sink.remove(
        Metrics.GroupRemoveMetricMessage(
          Metrics.MaxGroupOffsetLagMetric,
          "cluster",
          "group"
        )
      )
      sink.remove(
        Metrics.GroupRemoveMetricMessage(
          Metrics.MaxGroupTimeLagMetric,
          "cluster",
          "group"
        )
      )
      val labels = Array[String]("cluster_name", "group")
      val labelVals = Array[String]("cluster", "group")
      fixture.registry.getSampleValue(
        "kafka_consumergroup_group_max_lag",
        labels,
        labelVals
      ) should be(null)
    }

    "should get the correct global label names and values for the given cluster" in {
      fixture =>
        val clustersGlobalValuesMap = Map(
          "clusterA" -> Map("environment" -> "integration", "location" -> "ny"),
          "clusterB" -> Map("environment" -> "production"),
          "clusterC" -> Map.empty[String, String]
        )
        val sink = PrometheusEndpointSink(
          new PrometheusEndpointSinkConfig(
            "PrometheusEndpointSink",
            List(""),
            fixture.config
          ),
          Metrics.definitions,
          clustersGlobalValuesMap,
          fixture.server,
          fixture.registry
        ).asInstanceOf[PrometheusEndpointSink]
        sink.globalLabelNames shouldEqual List("environment", "location")
        sink.getGlobalLabelValuesOrDefault("clusterA") shouldEqual List(
          "integration",
          "ny"
        )
        sink.getGlobalLabelValuesOrDefault("clusterB") shouldEqual List(
          "production",
          ""
        )
        sink.getGlobalLabelValuesOrDefault("clusterC") shouldEqual List("", "")
        sink.getGlobalLabelValuesOrDefault("strimzi-cluster") shouldEqual List(
          "",
          ""
        )
    }

    "should add blank value for the cluster label for the cluster label is not specified for the cluster" in {
      fixture =>
        val clustersGlobalValuesMap = Map.empty[String, Map[String, String]]
        val sink = PrometheusEndpointSink(
          new PrometheusEndpointSinkConfig(
            "PrometheusEndpointSink",
            List(""),
            fixture.config
          ),
          Metrics.definitions,
          clustersGlobalValuesMap,
          fixture.server,
          fixture.registry
        ).asInstanceOf[PrometheusEndpointSink]
        sink.globalLabelNames shouldEqual List.empty
        sink.getGlobalLabelValuesOrDefault("clusterA") shouldEqual List.empty
        sink.getGlobalLabelValuesOrDefault("clusterB") shouldEqual List.empty
        sink.getGlobalLabelValuesOrDefault("clusterC") shouldEqual List.empty
        sink.getGlobalLabelValuesOrDefault(
          "strimzi-cluster"
        ) shouldEqual List.empty
    }
  }

}
