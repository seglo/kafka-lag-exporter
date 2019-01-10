package com.lightbend.sparkeventsexporter
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.streaming.StreamingQueryListener

object Exporter {
  def apply(session: SparkSession): Exporter = new Exporter(session)
}

class Exporter(session: SparkSession) {
  val listener: StreamingQueryListener = MetricsStreamingQueryListener()
  session.streams.addListener(listener)
}
