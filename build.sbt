import com.typesafe.sbt.packager.docker.{Cmd, ExecCmd}
import Dependencies._
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.{dockerCommands, dockerUsername}

lazy val updateHelmChartVersions = taskKey[Unit]("Update Helm Chart versions")

lazy val kafkaLagExporter =
  Project(id = "kafka-lag-exporter", base = file("."))
    .enablePlugins(JavaAppPackaging)
    .enablePlugins(DockerPlugin)
    .settings(commonSettings)
    .settings(
      name := "kafka-lag-exporter",
      description := "Kafka lag exporter finds and reports Kafka consumer group lag metrics",
      libraryDependencies ++= Vector(
        LightbendConfig,
        Kafka,
        AkkaTyped,
        AkkaSlf4j,
        Fabric8Model,
        Fabric8Client,
        Prometheus,
        PrometheusHotSpot,
        PrometheusHttpServer,
        ScalaJava8Compat,
        Logback,
        ScalaTest,
        AkkaTypedTestKit,
        MockitoScala,
        AlpakkaKafkaTestKit,
        AkkaHttp
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
  organization := "com.lightbend.kafkalagexporter",
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
