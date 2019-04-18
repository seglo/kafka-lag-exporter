package com.lightbend.kafka.kafkalagexporter.integration
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import com.lightbend.kafka.kafkalagexporter.Metrics
import org.scalatest.{Assertion, Matchers}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex

/**
 * Test utilities to parse the Prometheus health endpoint to assert metrics in integration tests.
  */
trait PrometheusTestUtils extends Matchers {

  private val log: Logger = LoggerFactory.getLogger(getClass)

  def scrape(port: Int, rules: List[Rule])
            (implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext): Future[List[Result]] = {
    val request = HttpRequest(uri = s"http://localhost:$port/metrics")
    for {
      HttpResponse(StatusCodes.OK, _, entity, _) <- Http().singleRequest(request)
      body <- Unmarshal(entity).to[String]
    } yield {
      log.debug("Received metrics response body:\n{}", body)
      rules.map { rule =>
        val matches = rule.regex.findAllMatchIn(body)
        val groupResults = matches.flatMap(_.subgroups).toList
        Result(rule, groupResults)
      }
    }
  }

  object Rule {
    def create(clazz: Class[_], expectation: Int, labelValues: String*): Rule = {
      val metric = Metrics.metricDefinitions(clazz)
      val name = metric.name
      val labels = metric.label.zip(labelValues).map { case (k, v) => s"""$k="$v""""}.mkString(",")
      /*
       * Ex)
       * kafka_consumergroup_group_lag\{cluster_name="default",group="group-1-2",topic="topic-1-1",partition="0".*\}\s+(-?\d+\.\d+)
       * https://regex101.com/r/haxLfS/1
       */
      val regex = s"""$name\\{$labels.*\\}\\s+(-?\\d+\\.\\d+)""".r
      log.debug(s"Created regex: {}", regex.pattern.toString)
      Rule(regex, expectation)
    }
  }

  case class Rule(regex: Regex, expectation: Int)

  case class Result(rule: Rule, groupResults: List[String]) {
    def assert(): Assertion = {
      groupResults.length shouldBe 1
      rule.expectation.toDouble.toString shouldBe groupResults.head
    }
  }
}
