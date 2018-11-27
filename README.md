# Kafka Lag Exporter

## Build

1. Publish docker image `lightbend/kafka-lag-exporter`

```
sbt docker:publish
```

2. Deploy the Helm Chart

```
helm install ./kafka-lag-exporter --name kafka-lag-exporter --namespace lightbend --set bootstrapBrokers=my-cluster-kafka-bootstrap:9092 --debug
```

