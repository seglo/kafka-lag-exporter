# Spark Event Exporter

> Exports metrics from data in Spark's `StreamingQueryListener` events 

Spark Event Exporter is a library you can include in your Spark driver application which can output several performance
metrics including Kafka client lag, lag in seconds, last read offset, as well as input and processed records per 
second per streaming source.

Spark Event Exporter uses the Spark `StreamingQueryListener` to obtain this event data which can then be directly
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