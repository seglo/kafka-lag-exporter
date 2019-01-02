import com.typesafe.sbt.packager.docker.{Cmd, ExecCmd}

name := "kafka-lag-exporter"
version := "0.3.0"
scalaVersion := "2.12.7"

val akkaVersion = "2.5.18"
val prometheusClientVersion = "0.5.0"
val fabric8Version = "4.1.0"

libraryDependencies ++= Seq(
  "com.typesafe"      %  "config"                   % "1.3.2",
  "org.apache.kafka"  %% "kafka"                    % "2.1.0",
  "com.typesafe.akka" %% "akka-actor-typed"         % akkaVersion,
  "com.typesafe.akka" %% "akka-slf4j"               % akkaVersion,
  "ch.qos.logback"    %  "logback-classic"          % "1.2.3",
  "io.prometheus"     %  "simpleclient"             % prometheusClientVersion,
  "io.prometheus"     %  "simpleclient_hotspot"     % prometheusClientVersion,
  "io.prometheus"     %  "simpleclient_httpserver"  % prometheusClientVersion,
  "io.fabric8"        %  "kubernetes-model"         % fabric8Version,
  "io.fabric8"        %  "kubernetes-client"        % fabric8Version,
  "org.scalatest"     %% "scalatest"                % "3.0.5"     % Test,
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test
)

enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)

dockerUsername := Some("lightbend")
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
)

lazy val updateHelmChartVersions = taskKey[Unit]("Update Helm Chart versions")

updateHelmChartVersions := {
  import scala.sys.process._
  s"./scripts/update_chart.sh ${version.value}" !
}

compile in Compile := (compile in Compile).dependsOn(updateHelmChartVersions).value