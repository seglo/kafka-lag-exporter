name := "kafka-lag-exporter"
version := "0.1"
scalaVersion := "2.12.7"

val prometheusJavaClientVersion = "0.5.0"

libraryDependencies ++= Seq(
  "com.typesafe" % "config" % "1.3.2",
  "org.apache.kafka" %% "kafka" % "2.1.0",
  "com.typesafe.akka" %% "akka-actor-typed" % "2.5.18",
  "io.prometheus" % "simpleclient" % prometheusJavaClientVersion,
  "io.prometheus" % "simpleclient_hotspot" % prometheusJavaClientVersion,
  "io.prometheus" % "simpleclient_httpserver" % prometheusJavaClientVersion,
  "org.scalatest" %% "scalatest" % "3.0.5" % "test"
)

enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)

dockerUsername := Some("lightbend")