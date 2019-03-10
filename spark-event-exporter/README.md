# Spark Event Exporter

> Exports metrics from data in Spark's `StreamingQueryListener` events

Spark Event Exporter is a library you can include in your Spark driver application that can output several performance
metrics including Kafka client lag, lag in seconds, last read offset, as well as input and processed records per
second per streaming source.

Spark Event Exporter uses the Spark `StreamingQueryListener` to obtain this event data that can then be directly
exported as a Prometheus endpoint, or integrated into Spark's own metrics system as custom metrics for the Spark
driver application.

The following metrics are exported.

* `spark_kafka_last_offset` - The last offset read for a partition.
* `spark_kafka_last_offset_lag` - The lag for a partition.
* `spark_kafka_last_offset_lag_seconds` - The extrapolated lag in seconds for a partition.
* `spark_kafka_input_records_per_second` - The input records per second for a Spark streaming source.
* `spark_kafka_processed_records_per_second` - The processed records per second for a Spark streaming source.

## Documentation TODO:

- Configuration
- Metrics sink configuration
- Metric labels

## Building Yourself

[SBT] is used to build this project.

* Run `sbt test` to compile everything and run the tests
* Run `sbt package` to create the binary artifacts
* Run `sbt publishLocal` to create artifacts and publish to your local Ivy repository
* Run `sbt publishM2` to create artifacts and publish to your local Maven repository
* Run `sbt release` to upgrade to the next version and publish Ivy artifacts to bintray

## Change log

0.3.6

* Final refinements before open sourcing the project.

0.3.5

* Encode labels into Codahale metric names using key=value convention to make them easier to parse.

0.3.2

* Initial release

