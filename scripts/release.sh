#!/usr/bin/env bash

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null && pwd )"

cd $DIR/..

# Upgrade version in `./build.sbt` and run `compile` and `test` targets.  A pre-compile task will automatically
# update the version in the Helm Chart.
echo Compile, test
sbt clean compile test

# Package chart
./scripts/package_chart.sh

# Tag repo and increment version
echo Release Spark Event Exporter and upgrade version
sbt release