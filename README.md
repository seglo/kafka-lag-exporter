# Kafka Lag Exporter

> A Kafka consumer group lag exporter for Kubernetes

The Kafka Lag Exporter is a Prometheus Exporter which will calculate the consumer lag for all consumer groups running
in a Kafka cluster.  It exports several consumer group related metrics, including an extrapolation of consumer group
lag in seconds.  

We can calculate a reasonable approximation of consumer lag in seconds by applying a linear extrapolation formula to
predict the time that a consumer will reach the latest partition offset available based on previously measured 
consumer group consumed offsets for the same partition.  

For each poll interval we associate all the latest consumed offsets with current system time (wall clock).  After at 
least two measurements are made we can extrapolate at what time an arbitrary offset in the future will be consumed.  As 
a refresher, linear interpolation and extrapolation is just estimating a point on a slope and estimating its 
coordinates. [Read this post for more details.](https://math.tutorvista.com/calculus/extrapolation.html)

## Metrics

The following metrics are exposed:

* `kafka_consumergroup_group_lag_seconds` - Extrapolated lag in seconds for each partition.
* `kafka_consumergroup_group_max_lag_seconds` - Max extrapolated lag in seconds for each consumer group.
* `kafka_consumergroup_group_lag` - Lag in offsets for each partition (latest offset - last consumed offset).
* `kafka_consumergroup_group_max_lag` - Max offset lag for each consumer group.
* `kafka_consumergroup_group_offset` - Last consumed offset for each consumer group partition.
* `kafka_consumergroup_latest_offset` - Latest offset available for each partition.

## Configuration

Details for configuration for the Helm Chart can be found in the [`values.yaml`](./charts/kafka-lag-exporter/values.yaml)
file of the accompanying Helm Chart.

## Install with Helm

Install the chart from the local file system with `helm install ./charts/kafka-lag-exporter`.

### Examples

Install with statically defined cluster at the CLI.

```
helm install ./charts/kafka-lag-exporter \
  --name kafka-lag-exporter \
  --namespace myproject \
  --set clusters\[0\].name=my-cluster \
  --set clusters\[0\].bootstrapBrokers=my-cluster-kafka-bootstrap:9092
```

Enable the Strimzi Kafka discovery feature.

```
helm install ./charts/kafka-lag-exporter \
  --name kafka-lag-exporter \
  --namespace myproject \
  --set image.pullPolicy=Always \
  --set logLevel=DEBUG \
  --set watchers.strimzi=true \
  --debug
```

Run a debug install (`DEBUG` logging, debug helm chart install, force docker pull policy to `Always`).

```
helm install ./charts/kafka-lag-exporter \
  --name kafka-lag-exporter \
  --namespace myproject \
  --set image.pullPolicy=Always \
  --set logLevel=DEBUG \
  --set clusters\[0\].name=my-cluster \
  --set clusters\[0\].bootstrapBrokers=my-cluster-kafka-bootstrap.myproject:9092 \
  --debug
```

### View the health endpoint

To view the Prometheus health endpoint from outside your Kubernetes cluster, use `kubectl port-forward`.

Ex)

```
kubectl port-forward service/kafka-lag-exporter 8080:80 --namespace myproject
```

### Exporter logs

To view the logs of the exporter, identify the pod name of the exporter and use the `kubectl logs` command.

Ex)

```
kubectl logs {POD_ID} --namespace myproject -f
```

## Release Process

1. Run `./scripts/release.sh` which will do the following:
  * Run `compile` and `test` targets.  A pre-compile task will automatically update the version in the Helm Chart.
  * Publish docker image to DockerHub at `lightbend/kafka-lag-exporter`.  If not publishing to `lightbend` repository, 
     update `./build.sbt` file with the correct repository, or publish locally instead (`sbt docker:publishLocal`).
  * Bundle Helm Chart into a tarball artifact.  The `helm package` command will output the artifact in the CWD it is 
     executed from.
2. Upload the tarball to a Helm Chart Repository.
3. Tag the release, Ex) `git tag -a 0.1.0 -m "0.1.0" && git push origin --tags`.
4. Update the project version in `./build.sbt`.

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

## Strimzi Kafka Cluster Watcher

Not ready, don't use yet.