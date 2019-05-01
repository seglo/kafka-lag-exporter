#!/usr/bin/env bash

set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null && pwd )"

APP_VERSION=$1
DOCKER_REPOSITORY=$2
DOCKER_REPOSITORY_ESCAPED=$(echo $DOCKER_REPOSITORY | sed -e 's/\\/\\\\/g; s/\//\\\//g; s/&/\\\&/g')
VERSION=`echo $1|sed -r 's/(\S+)-(\S+)/\1/'` # Strip out -SNAPSHOT if it exists
SNAPSHOT=`echo $1|sed -r 's/(\S+)-(\S+)/\2/'` # Capture SNAPSHOT if it exists

echo Update Chart.yaml appVersion to $APP_VERSION and version to $VERSION
sed -i -r "s/^(\s*)(appVersion\s*:\s.*$)/\1appVersion: \"${APP_VERSION}\"/" $DIR/../charts/kafka-lag-exporter/Chart.yaml
sed -i -r "s/^(\s*)(version\s*:\s.*$)/\1version: ${VERSION}/" $DIR/../charts/kafka-lag-exporter/Chart.yaml

echo Update values.yaml docker image tag to $APP_VERSION
sed -i -r "s/^(\s*)(tag\s*:\s.*$)/\1tag: ${APP_VERSION}/" $DIR/../charts/kafka-lag-exporter/values.yaml

echo Update values.yaml docker repository to $DOCKER_REPOSITORY
sed -i -r "s/^(\s*)(repository\s*:\s.*$)/\1repository: ${DOCKER_REPOSITORY_ESCAPED}/" $DIR/../charts/kafka-lag-exporter/values.yaml

if [[ SNAPSHOT == "SNAPSHOT" ]]; then
    sed -i -r "s/^(\s*)(pullPolicy\s*:\s.*$)/\1pullPolicy: Always/" $DIR/../charts/kafka-lag-exporter/values.yaml
else
    sed -i -r "s/^(\s*)(pullPolicy\s*:\s.*$)/\1pullPolicy: IfNotPresent/" $DIR/../charts/kafka-lag-exporter/values.yaml
fi
