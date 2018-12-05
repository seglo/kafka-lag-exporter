# Kafka Lag Exporter

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

3. Test deploy the Helm Chart.  See `./charts/kafka-lag-exporter/values.yaml` for configuration options.  Ex)

```
helm install ./kafka-lag-exporter \
  --name kafka-lag-exporter \
  --namespace lightbend \
  --set bootstrapBrokers=my-cluster-kafka-bootstrap:9092 \
  --set image.pullPolicy=Always \
  --set logLevel=DEBUG \
  --debug
```

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