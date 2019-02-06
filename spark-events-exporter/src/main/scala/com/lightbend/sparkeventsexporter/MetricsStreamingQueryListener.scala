package com.lightbend.sparkeventsexporter
import org.apache.spark.sql.streaming.StreamingQueryListener
import org.apache.spark.sql.streaming.StreamingQueryListener.{QueryProgressEvent, QueryStartedEvent, QueryTerminatedEvent}

object MetricsStreamingQueryListener {
  def apply(): MetricsStreamingQueryListener = new MetricsStreamingQueryListener
}

class MetricsStreamingQueryListener extends StreamingQueryListener() {
  override def onQueryStarted(event: QueryStartedEvent): Unit = ()
  override def onQueryTerminated(event: QueryTerminatedEvent): Unit = ()
  override def onQueryProgress(event: QueryProgressEvent): Unit = {
    val qp = QueryProgress.parseProgress(event.progress)
    println(s"MetricsStreamingQueryListener:\n$qp")
  }
}
