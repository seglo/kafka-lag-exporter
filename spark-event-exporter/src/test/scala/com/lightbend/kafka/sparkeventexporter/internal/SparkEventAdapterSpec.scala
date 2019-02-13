package com.lightbend.kafka.sparkeventexporter.internal

import com.lightbend.kafka.kafkametricstools
import com.lightbend.kafka.kafkametricstools.Domain.{Measurements, PartitionOffsets, TopicPartition}
import org.mockito.MockitoSugar
import org.scalatest.{Matchers, _}

class SparkEventAdapterSpec extends FreeSpec with Matchers with kafkametricstools.TestData with MockitoSugar {
  "SparkEventAdapterSpec" - {
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

      val offsets: PartitionOffsets = SparkEventAdapter.parseEndOffsets(endOffsetsJson, 0)

      offsets shouldBe Map(
        TopicPartition("call-record-pipeline-seglo.cdr-validator.out-1", 0) -> Measurements.Single(12477, 0),
        TopicPartition("call-record-pipeline-seglo.cdr-validator.out-1", 1) -> Measurements.Single(12293, 0),
        TopicPartition("call-record-pipeline-seglo.cdr-validator.out-1", 2) -> Measurements.Single(11274, 0)
      )
    }
  }
}
