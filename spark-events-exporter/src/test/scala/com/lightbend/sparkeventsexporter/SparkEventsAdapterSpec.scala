package com.lightbend.sparkeventsexporter

import com.lightbend.kafkaclientmetrics
import com.lightbend.kafkaclientmetrics.Domain.{Measurements, PartitionOffsets, TopicPartition}
import org.mockito.MockitoSugar
import org.scalatest.{Matchers, _}

class SparkEventsAdapterSpec extends FreeSpec with Matchers with kafkaclientmetrics.TestData with MockitoSugar {
  "SparkEventsAdapterSpec" - {
    "parseEndOffsets" in {
      val endOffsetsJson =
        """
          |{
          |  "call-record-pipeline-seglo.cdr-validator.out-1" : {
          |    "0" : 12477,
          |    "1" : 12293,
          |    "2" : 11274
          |  }
          |}
        """.stripMargin

      val offsets: PartitionOffsets = SparkEventsAdapter.parseEndOffsets(endOffsetsJson, 0)

      offsets shouldBe Map(
        TopicPartition("call-record-pipeline-seglo.cdr-validator.out-1", 0) -> Measurements.Single(12477, 0),
        TopicPartition("call-record-pipeline-seglo.cdr-validator.out-1", 1) -> Measurements.Single(12293, 0),
        TopicPartition("call-record-pipeline-seglo.cdr-validator.out-1", 2) -> Measurements.Single(11274, 0)
      )
    }
  }
}
