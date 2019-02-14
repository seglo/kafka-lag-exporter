package org.apache.spark.lightbend.sparkeventexporter

import com.codahale.metrics.{ Counter, Gauge, MetricRegistry }
import org.apache.spark.metrics.source.Source

class SparkEventExporterSource extends Source with Serializable {
  private val _metricRegistry = new MetricRegistry
  override def sourceName: String = "SparkEventExporter"
  override def metricRegistry: MetricRegistry = _metricRegistry

//  val gauge = new Gauge[Double] {
//    override def getValue: Double = 47d
//  }
//
//  _metricRegistry.register("testone", gauge)
//
//  def testTwo() = {
//
//    val gauge = new Gauge[Double] {
//      override def getValue: Double = 47d
//    }
//
//    _metricRegistry.register("testtwo", gauge)
//  }
}

