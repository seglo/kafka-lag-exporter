#!/usr/bin/env bash

DIR="$(cd "$(dirname "$0")" && pwd)"

docker run -p 8000:8000 \
    -v $DIR/application.conf:/tmp/application.conf \
    lightbend/kafka-lag-exporter:0.4.0 \
    /opt/docker/bin/kafka-lag-exporter -Dconfig.file=/tmp/application.conf
