#!/usr/bin/env bash

set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null && pwd )"

VERSION=$1
RELEASE_URL="https://github.com/lightbend/kafka-lag-exporter/releases/download/v$VERSION/kafka-lag-exporter-$VERSION.tgz"
RELEASE_URL_ESCAPED=$(echo $RELEASE_URL | sed -e 's/\\/\\\\/g; s/\//\\\//g; s/&/\\\&/g')

echo Update README.md helm install command to:
echo helm install $RELEASE_URL
sed -i -r "s/helm install \S*/helm install ${RELEASE_URL_ESCAPED}/g" $DIR/../README.md