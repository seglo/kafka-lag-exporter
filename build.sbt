import com.typesafe.sbt.packager.docker.{Cmd, ExecCmd}
import Dependencies._
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.{dockerCommands, dockerUsername}

lazy val root =
  Project(id = "root", base = file("."))
    .settings(
      name := "root",
      skip in publish := true
    )
    .withId("root")
    .settings(commonSettings)
    .aggregate(
      kafkaMetricsTools,
      kafkaLagExporter,
      sparkEventExporter
    )

lazy val kafkaMetricsTools =
  module("kafka-metrics-tools")
    .settings(
      description := "Tools to help get and report Kafka client metrics",
      libraryDependencies ++= Vector(
        Kafka,
        AkkaTyped,
        AkkaSlf4j,
        Logback,
        Prometheus,
        PrometheusHotSpot,
        PrometheusHttpServer,
        DropwizardMetrics,
        ScalaJava8Compat,
        ScalaTest,
        AkkaTypedTestKit,
        MockitoScala
      )
    )

lazy val sparkEventExporter =
  module("spark-event-exporter")
    .dependsOn(kafkaMetricsTools % "compile->compile;test->test")
    .settings(
      description := "Spark event exporter exposes offset and throughput related metrics for Spark Streaming apps",
      libraryDependencies ++= Vector(
        Spark,
        SparkSql,
        SparkSqlKafka,
        ScalaTest,
        AkkaTypedTestKit,
        MockitoScala
      )
    )

lazy val updateHelmChartVersions = taskKey[Unit]("Update Helm Chart versions")

lazy val kafkaLagExporter =
  module("kafka-lag-exporter")
    .dependsOn(kafkaMetricsTools % "compile->compile;test->test")
    .enablePlugins(JavaAppPackaging)
    .enablePlugins(DockerPlugin)
    .settings(
      description := "Kafka lag exporter finds and reports Kafka consumer group lag metrics",
      libraryDependencies ++= Vector(
        LightbendConfig,
        Kafka,
        AkkaTyped,
        AkkaSlf4j,
        Fabric8Model,
        Fabric8Client,
        Logback,
        ScalaTest,
        AkkaTypedTestKit,
        MockitoScala
      ),
      dockerUsername := Some("lightbend"),
      // Based on best practices found in OpenShift Creating images guidelines
      // https://docs.openshift.com/container-platform/3.10/creating_images/guidelines.html
      dockerCommands := Seq(
        Cmd("FROM",           "centos:7"),
        Cmd("RUN",            "yum -y install java-1.8.0-openjdk-headless && yum clean all -y"),
        Cmd("RUN",            "useradd -r -m -u 1001 -g 0 kafkalagexporter"),
        Cmd("ADD",            "opt /opt"),
        Cmd("RUN",            "chgrp -R 0 /opt && chmod -R g=u /opt"),
        Cmd("WORKDIR",        "/opt/docker"),
        Cmd("USER",           "1001"),
        ExecCmd("CMD",        "/opt/docker/bin/kafka-lag-exporter", "-XX:+UnlockExperimentalVMOptions", "-XX:+UseCGroupMemoryLimitForHeap"),
      ),
      updateHelmChartVersions := {
        import scala.sys.process._
        s"./scripts/update_chart.sh ${version.value}" !
      },
      compile in Compile := (compile in Compile).dependsOn(updateHelmChartVersions).value,
      publishArtifact in (Compile, packageDoc) := false,
      publishArtifact in (Compile, packageSrc) := false,
      skip in publish := true
    )

lazy val commonSettings = Seq(
  organization := "com.lightbend.kafka",
  bintrayOrganization := Some("lightbend"),
  bintrayRepository := "pipelines-internal",
  publishMavenStyle := false,
  bintrayOmitLicense := true,
  scalaVersion := Version.Scala,
  scalacOptions ++= Seq(
    "-encoding", "UTF-8",
    "-target:jvm-1.8",
    "-Xlog-reflective-calls",
    "-Xlint",
    "-Ywarn-unused",
    "-Ywarn-unused-import",
    "-deprecation",
    "-feature",
    "-language:_",
    "-unchecked"
  ),

  scalacOptions in (Compile, console) := (scalacOptions in (Global)).value.filter(_ == "-Ywarn-unused-import"),
  scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value,
)

def module(moduleId: String): Project = {
  Project(id = moduleId, base = file(moduleId))
    .settings(
      name := moduleId
    )
    .withId(moduleId)
    .settings(commonSettings)
}