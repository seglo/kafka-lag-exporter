/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{FreeSpec, Matchers}

class AppConfigSpec extends FreeSpec with Matchers {

  "AppConfig" - {
    "should parse static clusters" in {
      val config: Config = loadConfig(s"""
                                         |kafka-lag-exporter {
                                         |  clusters = [
                                         |    {
                                         |       name = "clusterA"
                                         |       bootstrap-brokers = "b-1.cluster-a.xyzcorp.com:9092,b-2.cluster-a.xyzcorp.com:9092"
                                         |       consumer-properties = {
                                         |         client.id = "consumer-client-id"
                                         |       }
                                         |       admin-client-properties = {
                                         |         client.id = "admin-client-id"
                                         |       }
                                         |       labels = {
                                         |         environment= "integration"
                                         |         location = "ny"
                                         |       }
                                         |    }
                                         |    {
                                         |       name = "clusterB"
                                         |       bootstrap-brokers = "b-1.cluster-b.xyzcorp.com:9092,b-2.cluster-b.xyzcorp.com:9092"
                                         |       labels = {
                                         |         environment= "production"
                                         |       }
                                         |    }
                                         |    {
                                         |       name = "clusterC"
                                         |       bootstrap-brokers = "c-1.cluster-b.xyzcorp.com:9092,c-2.cluster-b.xyzcorp.com:9092"
                                         |    }
                                         |  ]
                                         |}""".stripMargin)

      val appConfig = AppConfig(config)

      appConfig.clusters.length shouldBe 3
      appConfig.clusters(0).name shouldBe "clusterA"
      appConfig.clusters(0).bootstrapBrokers shouldBe "b-1.cluster-a.xyzcorp.com:9092,b-2.cluster-a.xyzcorp.com:9092"
      appConfig.clusters(0).consumerProperties("client.id") shouldBe "consumer-client-id"
      appConfig.clusters(0).adminClientProperties("client.id") shouldBe "admin-client-id"
      appConfig.clusters(0).labels("environment") shouldBe "integration"
      appConfig.clusters(0).labels("location") shouldBe "ny"
      appConfig.clusters(1).name shouldBe "clusterB"
      appConfig.clusters(1).bootstrapBrokers shouldBe "b-1.cluster-b.xyzcorp.com:9092,b-2.cluster-b.xyzcorp.com:9092"
      appConfig.clusters(1).consumerProperties shouldBe Map.empty
      appConfig.clusters(1).adminClientProperties shouldBe Map.empty
      appConfig.clusters(1).labels("environment") shouldBe "production"
      appConfig.clusters(2).name shouldBe "clusterC"
      appConfig.clusters(2).bootstrapBrokers shouldBe "c-1.cluster-b.xyzcorp.com:9092,c-2.cluster-b.xyzcorp.com:9092"
      appConfig.clusters(2).consumerProperties shouldBe Map.empty
      appConfig.clusters(2).adminClientProperties shouldBe Map.empty
      appConfig.clusters(2).labels shouldBe Map.empty
    }
  }

  private def loadConfig(configStr: String): Config = {
    ConfigFactory
      .parseString(configStr)
      .withFallback(ConfigFactory.load())
  }
}
