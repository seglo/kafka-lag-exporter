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
                                         |    }
                                         |    {
                                         |       name = "clusterB"
                                         |       bootstrap-brokers = "b-1.cluster-b.xyzcorp.com:9092,b-2.cluster-b.xyzcorp.com:9092"
                                         |    }
                                         |  ]
                                         |}""".stripMargin)

      val appConfig = AppConfig(config)

      appConfig.clusters.length shouldBe 2
      appConfig.clusters(0).name shouldBe "clusterA"
      appConfig.clusters(0).bootstrapBrokers shouldBe "b-1.cluster-a.xyzcorp.com:9092,b-2.cluster-a.xyzcorp.com:9092"
      appConfig.clusters(0).securityProtocol shouldBe "PLAINTEXT"
      appConfig.clusters(1).name shouldBe "clusterB"
      appConfig.clusters(1).bootstrapBrokers shouldBe "b-1.cluster-b.xyzcorp.com:9092,b-2.cluster-b.xyzcorp.com:9092"
      appConfig.clusters(1).securityProtocol shouldBe "PLAINTEXT"
    }

    "should parse static cluster with SSL info" in {
      val config: Config = loadConfig(s"""
                                         |kafka-lag-exporter {
                                         |  clusters = [
                                         |    {
                                         |       name = "clusterA"
                                         |       bootstrap-brokers = "b-1.cluster-a.xyzcorp.com:9092,b-2.cluster-a.xyzcorp.com:9092"
                                         |       security-protocol = "SASL_SSL"
                                         |       sasl-mechanism = "GSSAPI"
                                         |       sasl-jaas-config = "listener.name.sasl_ssl.scram-sha-256.sasl.jaas.config=com.example.ScramLoginModule required;"
                                         |    }
                                         |  ]
                                         |}""".stripMargin)

      val appConfig = AppConfig(config)

      appConfig.clusters.length shouldBe 1
      appConfig.clusters(0).name shouldBe "clusterA"
      appConfig.clusters(0).bootstrapBrokers shouldBe "b-1.cluster-a.xyzcorp.com:9092,b-2.cluster-a.xyzcorp.com:9092"
      appConfig.clusters(0).securityProtocol shouldBe "SASL_SSL"
      appConfig.clusters(0).saslMechanism shouldBe "GSSAPI"
      appConfig.clusters(0).saslJaasConfig shouldBe "listener.name.sasl_ssl.scram-sha-256.sasl.jaas.config=com.example.ScramLoginModule required;"
    }
  }

  private def loadConfig(configStr: String): Config = {
    ConfigFactory
      .parseString(configStr)
      .withFallback(ConfigFactory.load())
  }
}
