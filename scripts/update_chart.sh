#!/usr/bin/env bash

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null && pwd )"
VERSION=$1

echo Update Chart.yaml appVersion and version to $VERSION
sed -i -r "s/^(\s*)(appVersion\s*:\s.*$)/\1appVersion: \"${VERSION}\"/" $DIR/../charts/kafka-lag-exporter/Chart.yaml
sed -i -r "s/^(\s*)(version\s*:\s.*$)/\1version: ${VERSION}/" $DIR/../charts/kafka-lag-exporter/Chart.yaml

echo Update values.yaml docker image tag to $VERSION
sed -i -r "s/^(\s*)(tag\s*:\s.*$)/\1tag: ${VERSION}/" $DIR/../charts/kafka-lag-exporter/values.yaml
