package com.lightbend.sparkeventsexporter
import org.apache.spark.sql.streaming.StreamingQueryListener
import org.apache.spark.sql.streaming.StreamingQueryListener.{QueryProgressEvent, QueryStartedEvent, QueryTerminatedEvent}

object MetricsStreamingQueryListener {
  final case class Metric(name: String, timestamp: String, value: Double)

  def apply(): MetricsStreamingQueryListener = new MetricsStreamingQueryListener
}

class MetricsStreamingQueryListener extends StreamingQueryListener() {
  import MetricsStreamingQueryListener._

  val MaxDataPoints = 100
  // a mutable reference to an immutable container to buffer n data points
  var data: List[Metric] = Nil
  override def onQueryStarted(event: QueryStartedEvent): Unit = ()
  override def onQueryTerminated(event: QueryTerminatedEvent): Unit = ()
  override def onQueryProgress(event: QueryProgressEvent): Unit = {
    val queryProgress = event.progress

    println(s"MetricsListener queryProgress:\n${queryProgress.prettyJson}")
    // ignore zero-valued events
    //    if (queryProgress.numInputRows > 0) {
    //      val time = queryProgress.timestamp
    //      val input = Metric("in", time, event.progress.inputRowsPerSecond)
    //      val processed = Metric("proc", time, event.progress.processedRowsPerSecond)
    //      data = (input :: processed :: data).take(MaxDataPoints)
    //      //chart.applyOn(data)
    //    }
  }
}
