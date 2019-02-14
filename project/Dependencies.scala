import sbt._

object Version {
  val Scala      = "2.12.8"
  val Akka       = "2.5.20"
  val Prometheus = "0.5.0"
  val Fabric8    = "4.1.0"
  val Spark      = "2.4.0"
}

object Dependencies {
  val LightbendConfig       = "com.typesafe"          %  "config"                   % "1.3.2"
  val Kafka                 = "org.apache.kafka"      %% "kafka"                    % "2.1.0"
  val AkkaTyped             = "com.typesafe.akka"     %% "akka-actor-typed"         % Version.Akka
  val AkkaSlf4j             = "com.typesafe.akka"     %% "akka-slf4j"               % Version.Akka
  val Logback               = "ch.qos.logback"        %  "logback-classic"          % "1.2.3"
  val Prometheus            = "io.prometheus"         %  "simpleclient"             % Version.Prometheus
  val PrometheusHotSpot     = "io.prometheus"         %  "simpleclient_hotspot"     % Version.Prometheus
  val PrometheusHttpServer  = "io.prometheus"         %  "simpleclient_httpserver"  % Version.Prometheus
  val Fabric8Model          = "io.fabric8"            %  "kubernetes-model"         % Version.Fabric8
  val Fabric8Client         = "io.fabric8"            %  "kubernetes-client"        % Version.Fabric8
  val DropwizardMetrics     = "io.dropwizard.metrics" %  "metrics-core"             % "3.1.5"
  val Spark                 = "org.apache.spark"      %% "spark-core"               % Version.Spark
  val SparkSql              = "org.apache.spark"      %% "spark-sql"                % Version.Spark
  val SparkSqlKafka         = "org.apache.spark"      %% "spark-sql-kafka-0-10"     % Version.Spark

  val ScalaTest             = "org.scalatest"         %% "scalatest"                % "3.0.5"             % Test
  val AkkaTypedTestKit      = "com.typesafe.akka"     %% "akka-actor-testkit-typed" % Version.Akka        % Test
  val MockitoScala          = "org.mockito"           %% "mockito-scala"            % "1.0.8"             % Test
}