package com.lightbend.kafka.sparkeventexporter

import akka.actor.typed.ActorRef
import org.apache.spark.sql.streaming.StreamingQueryListener
import org.apache.spark.sql.streaming.StreamingQueryListener.{QueryProgressEvent, QueryStartedEvent, QueryTerminatedEvent}

object MetricsStreamingQueryListener {
  def apply(collector: ActorRef[OffsetCollector.Message]): MetricsStreamingQueryListener =
    new MetricsStreamingQueryListener(collector)
}

class MetricsStreamingQueryListener(collector: ActorRef[OffsetCollector.Message]) extends StreamingQueryListener() {
  override def onQueryStarted(event: QueryStartedEvent): Unit = ()
  override def onQueryTerminated(event: QueryTerminatedEvent): Unit = ()
  override def onQueryProgress(event: QueryProgressEvent): Unit = {
    val results = SparkEventAdapter.parseProgress(event.progress)
    collector ! OffsetCollector.QueryResults(results)
  }
}
