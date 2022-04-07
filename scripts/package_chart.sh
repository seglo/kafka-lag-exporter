#!/usr/bin/env bash

set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null && pwd )"

cd $DIR/..

CHARTS_DIR="repo"

# Bundle Kafka Lag Exporter Helm Chart into a tarball artifact.  The `helm package` command will output the artifact
# in the CHARTS_DIR.
echo Package helm chart
mkdir -p $CHARTS_DIR
rm -f $CHARTS_DIR/kafka-lag-exporter-*.tgz
helm package ./charts/kafka-lag-exporter -d $CHARTS_DIR