package org.apache.spark.lightbend.sparkeventexporter

import com.codahale.metrics.MetricRegistry
import org.apache.spark.metrics.source.Source

class SparkEventExporterSource extends Source with Serializable {
  private val _metricRegistry = new MetricRegistry
  override def sourceName: String = "SparkEventExporter"
  override def metricRegistry: MetricRegistry = _metricRegistry
}

