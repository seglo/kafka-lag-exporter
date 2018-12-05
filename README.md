# Kafka Lag Exporter

## Install with Helm

Download or install the Helm Chart from a Chart Repository.  To see a full list of configuration options consult the 
chart's `./charts/kafka-lag-exporter/values.yaml` file.

An example deployment.

```
helm install ./charts/kafka-lag-exporter \
  --name kafka-lag-exporter \
  --namespace myproject \
  --set image.pullPolicy=Always \
  --set logLevel=DEBUG \
  --set clusters\[0\].name=foobar \
  --set clusters\[0\].bootstrapBrokers=my-cluster-kafka-bootstrap:9092 \
  --debug
```

## Release Process

1. Upgrade version in `./build.sbt` and run `compile` and `test` targets.  A pre-compile task will automatically update
the version in the Helm Chart.

```
sbt clean compile test
```

2. Publish docker image to DockerHub at `lightbend/kafka-lag-exporter`.  If not publishing to `lightbend` repository, 
update `./build.sbt` file with the correct repository, or publish locally instead (`sbt docker:publishLocal`).

```
sbt docker:publish
```

3. Bundle Helm Chart into a tarball artifact.  The `helm package` command will output the artifact in the CWD it is 
executed from.

```
helm package ./charts/kafka-lag-exporter
```

4. Upload the tarball to a Helm Chart Repository.

## Testing with local `docker-compose.yaml`

A Docker Compose cluster with producers and multiple consumer groups is defined in `./docker/docker-compose.yaml`.  This
is useful to manually test the project locally, without K8s infrastructure.  These images are based on the popular
[`wurstmeister`](https://hub.docker.com/r/wurstmeister/kafka/) Apache Kafka Docker images.  Confirm you match up the 
version of these images with the correct version of Kafka you wish to test.

Remove any previous volume state.

```
docker-compose rm -f
```

Start up the cluster in the foreground.

```
docker-compose up
```