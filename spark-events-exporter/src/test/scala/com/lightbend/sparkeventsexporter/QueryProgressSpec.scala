package com.lightbend.sparkeventsexporter

import com.lightbend.kafkaclientmetrics
import org.mockito.MockitoSugar
import org.scalatest.{Matchers, _}

import Domain._

class QueryProgressSpec extends FreeSpec with Matchers with kafkaclientmetrics.TestData with MockitoSugar {
  "SourceOffsetsSpec" - {
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

      val offsets = QueryProgress.parseEndOffsets(endOffsetsJson)

      offsets shouldBe List(
        TopicPartitionOffset("call-record-pipeline-seglo.cdr-validator.out-1", 0, 12477),
        TopicPartitionOffset("call-record-pipeline-seglo.cdr-validator.out-1", 1, 12293),
        TopicPartitionOffset("call-record-pipeline-seglo.cdr-validator.out-1", 2, 11274)
      )
    }
  }
}
