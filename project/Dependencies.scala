import sbt._

object Version {
  val Scala = "2.12.16"
  val Akka = "2.6.20"
  val Prometheus = "0.15.0"
  val Fabric8 = "4.13.3"
  val Kafka = "3.2.2"
  val Testcontainers = "1.17.3"
  val IAMAuth = "1.1.4"
}

object Dependencies {
  /*
   * Dependencies on com.fasterxml.jackson.core:jackson-databind and com.fasterxml.jackson.core:jackson-core
   * conflict with Spark and Kafka, so in spark-committer exclude all transient dependencies in the
   * com.fasterxml.jackson.core organization from Kafka deps.
   *
   * Kafka dependencies on Java logging frameworks interfere with correctly setting app logging in spark-committer's
   * integration tests.
   */
  private val jacksonExclusionRule = ExclusionRule("com.fasterxml.jackson.core")
  private val log4jExclusionRule = ExclusionRule("log4j")
  private val slf4jExclusionRule = ExclusionRule("org.slf4j")

  val LightbendConfig = "com.typesafe" % "config" % "1.3.2"
  val Kafka =
    "org.apache.kafka" %% "kafka" % Version.Kafka excludeAll (jacksonExclusionRule, log4jExclusionRule, slf4jExclusionRule)
  val Akka = "com.typesafe.akka" %% "akka-actor" % Version.Akka
  val AkkaTyped = "com.typesafe.akka" %% "akka-actor-typed" % Version.Akka
  val AkkaSlf4j = "com.typesafe.akka" %% "akka-slf4j" % Version.Akka
  val AkkaStreams = "com.typesafe.akka" %% "akka-stream" % Version.Akka
  val AkkaStreamsProtobuf =
    "com.typesafe.akka" %% "akka-protobuf" % Version.Akka
  val AkkaInfluxDB =
    "com.lightbend.akka" %% "akka-stream-alpakka-influxdb" % "3.0.4"
  val Logback = "ch.qos.logback" % "logback-classic" % "1.2.11"
  val Prometheus = "io.prometheus" % "simpleclient" % Version.Prometheus
  val PrometheusHotSpot =
    "io.prometheus" % "simpleclient_hotspot" % Version.Prometheus
  val PrometheusHttpServer =
    "io.prometheus" % "simpleclient_httpserver" % Version.Prometheus
  val Fabric8Model = "io.fabric8" % "kubernetes-model" % Version.Fabric8
  val Fabric8Client = "io.fabric8" % "kubernetes-client" % Version.Fabric8
  val ScalaJava8Compat =
    "org.scala-lang.modules" %% "scala-java8-compat" % "1.0.2"
  val AkkaHttp = "com.typesafe.akka" %% "akka-http" % "10.2.10"
  val IAMAuthLib = "software.amazon.msk" % "aws-msk-iam-auth" % Version.IAMAuth

  /* Test */
  val AkkaTypedTestKit =
    "com.typesafe.akka" %% "akka-actor-testkit-typed" % Version.Akka % Test
  val ScalaTest = "org.scalatest" %% "scalatest" % "3.2.13" % Test
  val AkkaStreamsTestKit =
    "com.typesafe.akka" %% "akka-stream-testkit" % Version.Akka % Test
  val MockitoScala = "org.mockito" %% "mockito-scala" % "1.17.12" % Test
  val AlpakkaKafkaTestKit =
    "com.typesafe.akka" %% "akka-stream-kafka-testkit" % "2.0.7" % Test excludeAll (jacksonExclusionRule, log4jExclusionRule, slf4jExclusionRule)
  val TestcontainersKafka =
    "org.testcontainers" % "kafka" % Version.Testcontainers % Test
  val TestcontainersInfluxDb =
    "org.testcontainers" % "influxdb" % Version.Testcontainers % Test
}
