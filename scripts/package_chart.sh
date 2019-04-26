#!/usr/bin/env bash

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null && pwd )"

cd $DIR/..

# Publish docker image to DockerHub at `lightbend/kafka-lag-exporter`.  If not publishing to `lightbend` repository,
# update `./build.sbt` file with the correct repository, or publish locally instead (`sbt docker:publishLocal`).
echo Publish docker image
sbt docker:publish

# Bundle Kafka Lag Exporter Helm Chart into a tarball artifact.  The `helm package` command will output the artifact
# in the CWD it is executed from.
echo Bundle helm chart
helm package ./charts/kafka-lag-exporter