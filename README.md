# Kafka Metrics Tools

> A set of tools and libraries to expose Kafka performance metrics in the K8s and Prometheus ecosystem

## [Kafka Lag Exporter](./kafka-lag-exporter/README.md)

The Kafka Lag Exporter is a Prometheus Exporter which will calculate the consumer lag for all consumer groups running
in a Kafka cluster.  It exports several consumer group related metrics, including an extrapolation of consumer group
lag in seconds. 

## [Spark Event Exporter](./spark-event-exporter/README.md)

Spark Event Exporter is a library you can include in your Spark driver application which can output several performance
metrics including Kafka client lag, lag in seconds, last read offset, as well as input and processed records per 
second per streaming source.

# Release Process

1. Update Change log
2. Run `./scripts/release.sh` which will do the following:
  * Run `compile` and `test` targets.  A pre-compile task will automatically update the version in the Helm Chart.
  * Publish docker image to DockerHub at `lightbend/kafka-lag-exporter`.  If not publishing to `lightbend` repository, 
     update `./build.sbt` file with the correct repository, or publish locally instead (`sbt docker:publishLocal`).
  * Bundle Helm Chart into a tarball artifact.  The `helm package` command will output the artifact in the CWD it is 
     executed from.
  * Run `sbt-release` to upgrade to the next version and publish Ivy artifacts to bintray.
3. Upload the tarball to a Helm Chart Repository.

# Change log

0.3.1

* Default partition to 0 (instead of omitting it from being reported) when a consumer group returns no offset for a 
group partition
* Use `akkaSource` for actor path in logging

0.3.0

* Bugfix: Parse `poll-interval` in seconds
* Rename metric from `kafka_consumergroup_latest_offset` to `kafka_partition_latest_offset`
* Use JVM 8 experimental cgroup memory awareness flags when running exporter in container
* Use snakecase for metric label names
* Sample Grafana Dashboard

0.2.0

* Strimzi cluster auto discovery

0.1.0

* Initial release


