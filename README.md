# Kafka Metrics Tools

> A set of tools and libraries to expose Kafka performance metrics in the K8s and Prometheus ecosystem

## [Kafka Lag Exporter](./kafka-lag-exporter/README.md)

The Kafka Lag Exporter is a Prometheus Exporter which will calculate the consumer lag for all consumer groups running
in a Kafka cluster.  It exports several consumer group related metrics, including an extrapolation of consumer group
lag in seconds. See kafka-lag-exporter/README.md for more information.

## [Spark Event Exporter](./spark-event-exporter/README.md)

Spark Event Exporter is a library you can include in your Spark driver application which can output several performance
metrics including Kafka client lag, lag in seconds, last read offset, as well as input and processed records per
second per streaming source. See spark-event-exporter/README.md for more information.

This project was developed by [Sean Glover](https://github.com/seglo) at [Lightbend](https://www.lightbend.com). For a production-ready system with support for Kafka, Spark, with this project, Akka, Play, Lagom, and other tools on Kubernetes and OpenShift, see [Lightbend Platform](https://www.lightbend.com/lightbend-platform).
