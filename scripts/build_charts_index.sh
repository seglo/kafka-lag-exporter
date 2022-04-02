#!/usr/bin/env bash

set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null && pwd )"

cd $DIR/..

REMOTE_URL_BASE=$1
REMOTE_INDEX_URL=$2
CHARTS_DIR=".helm-release-packages"

EXISTING_INDEX=$(mktemp /tmp/index.yaml.XXXXXX)

REMOTE_CODE=$(curl -s -L -w "%{http_code}" $REMOTE_INDEX_URL -o $EXISTING_INDEX)
if [ $REMOTE_CODE -eq 200 ]; then
  echo Adding new packages to existing index
  helm repo index --merge $EXISTING_INDEX --url $REMOTE_URL_BASE $CHARTS_DIR
else
  echo Creating new index
  helm repo index --url $REMOTE_URL_BASE $CHARTS_DIR
fi

rm "${EXISTING_INDEX}"