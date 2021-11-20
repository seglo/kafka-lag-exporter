import sbt._

object Version {
  val Scala          = "2.12.11"
  val Akka           = "2.6.17"
  val Prometheus     = "0.12.0"
  val Fabric8        = "4.9.1"
  val Kafka          = "2.5.0"
  val Testcontainers = "1.15.3"
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

  val LightbendConfig        = "com.typesafe"            %  "config"                       % "1.3.2"
  val Kafka                  = "org.apache.kafka"        %% "kafka"                        % Version.Kafka excludeAll (jacksonExclusionRule, log4jExclusionRule, slf4jExclusionRule)
  val Akka                   = "com.typesafe.akka"       %% "akka-actor"                   % Version.Akka
  val AkkaTyped              = "com.typesafe.akka"       %% "akka-actor-typed"             % Version.Akka
  val AkkaSlf4j              = "com.typesafe.akka"       %% "akka-slf4j"                   % Version.Akka
  val AkkaStreams            = "com.typesafe.akka"       %% "akka-stream"                  % Version.Akka
  val AkkaStreamsProtobuf    = "com.typesafe.akka"       %% "akka-protobuf"                % Version.Akka
  val AkkaInfluxDB           = "com.lightbend.akka"      %% "akka-stream-alpakka-influxdb" % "3.0.3"
  val Logback                = "ch.qos.logback"          %  "logback-classic"              % "1.2.7"
  val Prometheus             = "io.prometheus"           %  "simpleclient"                 % Version.Prometheus
  val PrometheusHotSpot      = "io.prometheus"           %  "simpleclient_hotspot"         % Version.Prometheus
  val PrometheusHttpServer   = "io.prometheus"           %  "simpleclient_httpserver"      % Version.Prometheus
  val Fabric8Model           = "io.fabric8"              %  "kubernetes-model"             % Version.Fabric8
  val Fabric8Client          = "io.fabric8"              %  "kubernetes-client"            % Version.Fabric8
  val ScalaJava8Compat       = "org.scala-lang.modules"  %% "scala-java8-compat"           % "0.9.1"
  val AkkaHttp               = "com.typesafe.akka"       %% "akka-http"                    % "10.1.14"

  /* Test */
  val AkkaTypedTestKit       = "com.typesafe.akka"       %% "akka-actor-testkit-typed"  % Version.Akka           % Test
  val ScalaTest              = "org.scalatest"           %% "scalatest"                 % "3.1.4"                % Test
  val AkkaStreamsTestKit     = "com.typesafe.akka"       %% "akka-stream-testkit"       % Version.Akka           % Test
  val MockitoScala           = "org.mockito"             %% "mockito-scala"             % "1.0.10"               % Test
  val AlpakkaKafkaTestKit    = "com.typesafe.akka"       %% "akka-stream-kafka-testkit" % "2.0.7"                % Test excludeAll(jacksonExclusionRule, log4jExclusionRule, slf4jExclusionRule)
  val TestcontainersKafka    = "org.testcontainers"      %  "kafka"                     % Version.Testcontainers % Test
  val TestcontainersInfluxDb = "org.testcontainers"      %  "influxdb"                  % Version.Testcontainers % Test
}
