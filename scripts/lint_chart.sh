#!/usr/bin/env bash

set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null && pwd )"

cd $DIR/..

# Lint the helm chart for any possible errors
echo Lint helm chart
helm lint ./charts/kafka-lag-exporter