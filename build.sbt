import Dependencies._
import ReleasePlugin.autoImport._
import ReleaseKeys._
import ReleaseTransformations._
import com.typesafe.sbt.packager.docker.{Cmd, DockerPermissionStrategy, ExecCmd}
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport._
import com.typesafe.sbt.packager.docker.DockerApiVersion
import com.typesafe.sbt.packager.docker.DockerPlugin.UnixSeparatorChar
import sbt.IO

import java.time.format.DateTimeFormatter
import java.time.Instant
import scala.sys.process._

lazy val kafkaLagExporter =
  Project(id = "kafka-lag-exporter", base = file("."))
    .enablePlugins(AutomateHeaderPlugin)
    .enablePlugins(JavaAppPackaging)
    .enablePlugins(DockerPlugin)
    .configs(IntegrationTest.extend(Test))
    .settings(Defaults.itSettings)
    .settings(headerSettings(IntegrationTest))
    .settings(commonSettings)
    .settings(
      name := "kafka-lag-exporter",
      libraryDependencies ++= Vector(
        LightbendConfig,
        Kafka,
        Akka,
        AkkaTyped,
        AkkaSlf4j,
        AkkaStreams,
        AkkaStreamsProtobuf,
        AkkaInfluxDB,
        Fabric8Model,
        Fabric8Client,
        Prometheus,
        PrometheusHotSpot,
        PrometheusHttpServer,
        ScalaJava8Compat,
        AkkaHttp,
        Logback,
        IAMAuthLib,
        ScalaTest,
        AkkaTypedTestKit,
        MockitoScala,
        AkkaStreamsTestKit,
        AlpakkaKafkaTestKit,
        TestcontainersKafka,
        TestcontainersInfluxDb
      ),
      dockerApiVersion := Some(DockerApiVersion(1, 41)),
      dockerRepository := Option(System.getenv("DOCKER_REPOSITORY")).orElse(None),
      dockerUsername := Option(System.getenv("DOCKER_USERNAME")).orElse(Some("seglo")),
      dockerUpdateLatest := true,
      dockerPermissionStrategy := DockerPermissionStrategy.None,
      dockerExposedPorts := Seq(8000),
      // Based on best practices found in OpenShift Creating images guidelines
      // https://docs.openshift.com/container-platform/4.10/openshift_images/create-images.html
      dockerCommands := {
        // OCI Image Spec Annotations
        // https://github.com/opencontainers/image-spec/blob/main/annotations.md
        val labels = Map(
          "org.opencontainers.image.title" -> name.value,
          "org.opencontainers.image.description" -> description.value,
          "org.opencontainers.image.vendor" -> organizationName.value,
          "org.opencontainers.image.created" -> DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
          "org.opencontainers.image.authors" -> maintainer.value,
          "org.opencontainers.image.url" -> homepage.value.get.toString,
          "org.opencontainers.image.version" -> version.value,
          "org.opencontainers.image.licenses" -> licenses.value.map(l => l._1 + ": " + l._2).mkString(", ")
        ).map(l => Cmd("LABEL", l._1 + "=\"" + l._2 + "\""))
        val dockerBaseDirectory = (Docker / defaultLinuxInstallLocation).value
        val layerIdsAscending = (Docker / dockerLayerMappings).value.map(_.layerId).distinct.sortWith { (a, b) =>
          // Make the None (unspecified) layer the last layer
          a.getOrElse(Int.MaxValue) < b.getOrElse(Int.MaxValue)
        }
        val layerCopy = layerIdsAscending.map { layerId =>
          val files = dockerBaseDirectory.split(UnixSeparatorChar)(1)
          val path = layerId.map(i => s"$i/$files").getOrElse(s"$files")
          Cmd("COPY", s"$path /$files")
        }
        Seq(
          Cmd("FROM",           "redhat/ubi8")) ++
          labels ++ Seq(
          Cmd("RUN",            "yum -y install java-17-openjdk-headless && yum update -y && yum clean all -y"),
          Cmd("RUN",            "useradd -r -m -u 1001 kafkalagexporter")) ++
          layerCopy ++ Seq(
          Cmd("RUN",            "chgrp -R 1001 /opt && chmod -R g=u /opt && chmod +x /opt/docker/bin/kafka-lag-exporter"),
          Cmd("WORKDIR",        "/opt/docker"),
          Cmd("USER",           "1001")) ++
          dockerExposedPorts.value.map(p => Cmd("EXPOSE", p.toString)) ++ Seq(
          ExecCmd("CMD",        "/opt/docker/bin/kafka-lag-exporter",
            "-Dconfig.file=/opt/docker/conf/application.conf",
            "-Dlogback.configurationFile=/opt/docker/conf/logback.xml"),
        )
      },
      updateHelmChart := {
        import scala.sys.process._
        val repo = dockerAlias.value.withTag(None).toString
        s"./scripts/update_chart.sh ${version.value} $repo" !
      },
      skip in publish := true,
      parallelExecution in Test := false,
      releaseProcess := Seq[ReleaseStep](
        lintHelmChart,                          // Lint the Helm Chart for errors
        checkSnapshotDependencies,
        inquireVersions,
        runClean,
        runTest,
        setReleaseVersion,
        updateHelmChartRelease,                 // Update the Helm Chart
        publishDockerImage,                     // Publish the Docker images used by the chart
        packageChart,                           // Package the Helm Chart
//        buildChartsIndex,                       // Build Helm Charts index
        packageJavaApp,                         // Package the standalone Java App
        commitReleaseVersion,
        updateReadmeRelease,                    // Update the README.md with this version
        commitReadmeVersion,                    // Commit the README.md
        commitChartThisVersion,                 // Commit the Helm Chart
        tagRelease,
        githubReleaseDraft,                     // Create a GitHub release draft
//        publishChartsIndex,                     // Publish Helm Charts index
        setNextVersion,
        updateHelmChartNextVersion,             // Update the Helm Chart with the next snapshot version
        commitChartNextVersion,                 // Commit the Helm Chart
        commitNextVersion,
        pushChanges
      )
    )

lazy val commonSettings = Seq(
  description := "Kafka lag exporter finds and reports Kafka consumer group lag metrics",
  organization := "com.lightbend.kafkalagexporter",
  organizationName := "Lightbend Inc. <http://www.lightbend.com> (2018-2022), Sean Glover <https://seanglover.com/> (2022+)",
  organizationHomepage := Some(url("https://seanglover.com/")),
  homepage := Some(url("https://github.com/seglo/kafka-lag-exporter")),
  maintainer := "sean@seanglover.com",
  licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt")),
  scmInfo := Some(ScmInfo(homepage.value.get, "git@github.com:seglo/kafka-lag-exporter.git")),
  developers += Developer("contributors", "Contributors", maintainer.value, organizationHomepage.value.get),
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

lazy val updateHelmChart = taskKey[Unit]("Update Helm Chart")

def exec(cmd: String, errorMessage: String): Unit = {
  val e = cmd.!
  if (e != 0) sys.error(errorMessage)
}

lazy val lintHelmChart = ReleaseStep(action = st => {
  exec("./scripts/lint_chart.sh", "Error while linting Helm Chart")
  st
})

lazy val updateHelmChartRelease = ReleaseStep(action = st => {
  val (releaseVersion, _) = st.get(versions).getOrElse(sys.error("No versions are set! Was this release part executed before inquireVersions?"))
  val extracted = Project.extract(st)
  val repo = extracted.get(dockerAlias in thisProjectRef).withTag(None)
  exec(s"./scripts/update_chart.sh $releaseVersion $repo", "Error while updating Helm Chart")
  st
})

lazy val updateHelmChartNextVersion = ReleaseStep(action = st => {
  val (_, nextVersion) = st.get(versions).getOrElse(sys.error("No versions are set! Was this release part executed before inquireVersions?"))
  val extracted = Project.extract(st)
  val repo = extracted.get(dockerAlias in thisProjectRef).withTag(None)
  exec(s"./scripts/update_chart.sh $nextVersion $repo", "Error while updating Helm Chart")
  st
})

lazy val updateReadmeRelease = ReleaseStep(action = st => {
  val (releaseVersion, _) = st.get(versions).getOrElse(sys.error("No versions are set! Was this release part executed before inquireVersions?"))
  exec(s"./scripts/update_readme_version.sh $releaseVersion", "Error while updating README")
  st
})

lazy val commitReadmeVersion = ReleaseStep(action = st => {
  val (releaseVersion, _) = st.get(versions).getOrElse(sys.error("No versions are set! Was this release part executed before inquireVersions?"))
  val vcs = Project.extract(st).get(releaseVcs).getOrElse(sys.error("Aborting release. Working directory is not a repository of a recognized VCS."))
  val readme = (vcs.baseDir / "README.md").getCanonicalFile
  val base = vcs.baseDir.getCanonicalFile
  val relativeReadme = IO.relativize(base, readme).getOrElse("Version file [%s] is outside of this VCS repository with base directory [%s]!" format(readme, base))
  vcs.add(relativeReadme).!!
  vcs.commit(s"Update version in README for $releaseVersion", sign = false, signOff = false).!
  st
})

lazy val commitChartNextVersion = ReleaseStep(action = st => {
  val (_, nextVersion) = st.get(versions).getOrElse(sys.error("No versions are set! Was this release part executed before inquireVersions?"))
  val vcs = Project.extract(st).get(releaseVcs).getOrElse(sys.error("Aborting release. Working directory is not a repository of a recognized VCS."))
  val chartYaml = (vcs.baseDir / "charts/kafka-lag-exporter/Chart.yaml").getCanonicalFile
  val valuesYaml = (vcs.baseDir / "charts/kafka-lag-exporter/values.yaml").getCanonicalFile
  val base = vcs.baseDir.getCanonicalFile
  val relativeChartYaml = IO.relativize(base, chartYaml).getOrElse("Version file [%s] is outside of this VCS repository with base directory [%s]!" format(chartYaml, base))
  val relativeValuesYaml = IO.relativize(base, valuesYaml).getOrElse("Version file [%s] is outside of this VCS repository with base directory [%s]!" format(valuesYaml, base))
  vcs.add(relativeChartYaml).!!
  vcs.add(relativeValuesYaml).!!
  vcs.commit(s"Update versions in chart for $nextVersion", sign = false, signOff = false).!
  st
})

lazy val commitChartThisVersion = ReleaseStep(action = st => {
  val (releaseVersion, _) = st.get(versions).getOrElse(sys.error("No versions are set! Was this release part executed before inquireVersions?"))
  val vcs = Project.extract(st).get(releaseVcs).getOrElse(sys.error("Aborting release. Working directory is not a repository of a recognized VCS."))
  val chartYaml = (vcs.baseDir / "charts/kafka-lag-exporter/Chart.yaml").getCanonicalFile
  val valuesYaml = (vcs.baseDir / "charts/kafka-lag-exporter/values.yaml").getCanonicalFile
  val base = vcs.baseDir.getCanonicalFile
  val relativeChartYaml = IO.relativize(base, chartYaml).getOrElse("Version file [%s] is outside of this VCS repository with base directory [%s]!" format(chartYaml, base))
  val relativeValuesYaml = IO.relativize(base, valuesYaml).getOrElse("Version file [%s] is outside of this VCS repository with base directory [%s]!" format(valuesYaml, base))
  vcs.add(relativeChartYaml).!!
  vcs.add(relativeValuesYaml).!!
  vcs.commit(s"Update versions in chart for $releaseVersion", sign = false, signOff = false).!
  st
})

lazy val packageChart = ReleaseStep(action = st => {
  exec("./scripts/package_chart.sh", "Error while packaging Helm Chart")
  st
})

lazy val buildChartsIndex = ReleaseStep(action = st => {
  val (releaseVersion, _) = st.get(versions).getOrElse(sys.error("No versions are set! Was this release part executed before inquireVersions?"))
  exec(
    s"./scripts/build_charts_index.sh https://github.com/lightbend/kafka-lag-exporter/releases/download/v$releaseVersion/ https://seanglover.com/kafka-lag-exporter/repo/index.yaml",
    "Error while building Helm Charts index")
  st
})

lazy val publishChartsIndex = ReleaseStep(action = st => {
  exec("./scripts/publish_charts_index.sh", "Error while publishing Helm Charts index")
  st
})

lazy val githubReleaseDraft = ReleaseStep(action = st => {
  val (releaseVersion, _) = st.get(versions).getOrElse(sys.error("No versions are set! Was this release part executed before inquireVersions?"))
  exec(
    s"./scripts/github_release.sh lightbend/kafka-lag-exporter v$releaseVersion -- .helm-release-packages/kafka-lag-exporter-$releaseVersion.tgz ./target/universal/kafka-lag-exporter-$releaseVersion.zip",
    "Error while publishing GitHub release draft")
  st
})

lazy val publishDockerImage = ReleaseStep(
  action = { st: State =>
    val extracted = Project.extract(st)
    val ref = extracted.get(thisProjectRef)
    extracted.runAggregated(publish in Docker in ref, st)
  }
)

lazy val packageJavaApp = ReleaseStep(
  action = { st: State =>
    val extracted = Project.extract(st)
    val ref = extracted.get(thisProjectRef)
    extracted.runAggregated(packageBin in Universal in ref, st)
  }
)
