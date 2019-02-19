#!/usr/bin/env bash

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null && pwd )"

cd $DIR/..

# Exporter

# 1. Upgrade version in `./build.sbt` and run `compile` and `test` targets.  A pre-compile task will automatically
#    update the version in the Helm Chart.
echo Compile, test
sbt clean compile test

# 2. Publish docker image to DockerHub at `lightbend/kafka-lag-exporter`.  If not publishing to `lightbend` repository,
#    update `./build.sbt` file with the correct repository, or publish locally instead (`sbt docker:publishLocal`).
echo Publish docker image
sbt exporter/docker:publish

# 3. Bundle Kafka Lag Exporter Helm Chart into a tarball artifact.  The `helm package` command will output the artifact
#    in the CWD it is executed from.
echo Bundle helm chart
helm package ./charts/kafka-lag-exporter

# 4. Release Spark Event Exporter binaries to bintray, and upgrade version.
echo Release Spark Event Exporter and upgrade version
sbt release