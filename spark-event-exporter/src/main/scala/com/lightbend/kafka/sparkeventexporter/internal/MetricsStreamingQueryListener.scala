package com.lightbend.kafka.sparkeventexporter.internal

import akka.actor.typed.ActorRef
import org.apache.spark.sql.streaming.StreamingQueryListener
import org.apache.spark.sql.streaming.StreamingQueryListener.{QueryProgressEvent, QueryStartedEvent, QueryTerminatedEvent}

object MetricsStreamingQueryListener {
  def apply(collector: ActorRef[MetricCollector.Message]): MetricsStreamingQueryListener =
    new MetricsStreamingQueryListener(collector)
}

final class MetricsStreamingQueryListener private[internal](collector: ActorRef[MetricCollector.Message]) extends StreamingQueryListener() {
  override def onQueryStarted(event: QueryStartedEvent): Unit = ()
  override def onQueryTerminated(event: QueryTerminatedEvent): Unit = ()
  override def onQueryProgress(event: QueryProgressEvent): Unit = {
    val results = SparkEventAdapter.parseProgress(event.progress)
    collector ! MetricCollector.QueryResults(results)
  }
}
