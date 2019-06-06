#!/usr/bin/env bash

DIR="$(cd "$(dirname "$0")" && pwd)"

docker run -p 8000:8000 \
    -v $DIR:/opt/docker/conf/ \
    lightbend/kafka-lag-exporter:0.4.0 \
    /opt/docker/bin/kafka-lag-exporter \
    -Dconfig.file=/opt/docker/conf/application.conf \
    -Dlogback.configurationFile=/opt/docker/conf/logback.xml
