/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter.integration

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import com.lightbend.kafkalagexporter.Metrics
import com.lightbend.kafkalagexporter.MetricsSink.GaugeDefinition
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex

/**
 * Test utilities to parse the Prometheus health endpoint to assert metrics in integration tests.
 */
trait PrometheusUtils extends Matchers with ScalaFutures {

  private val log: Logger = LoggerFactory.getLogger(getClass)

  def scrape(port: Int, rules: Rule*)
            (implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext): Future[List[Result]] = {
    val request = HttpRequest(uri = s"http://localhost:$port/metrics")
    for {
      HttpResponse(StatusCodes.OK, _, entity, _) <- Http().singleRequest(request)
      body <- Unmarshal(entity).to[String]
    } yield {
      log.debug("Received metrics response body:\n{}", body)
      rules.toList.map { rule =>
        val matches = rule.regex.findAllMatchIn(body)
        val groupResults = matches.flatMap(_.subgroups).toList
        Result(rule, groupResults)
      }
    }
  }

  def scrapeAndAssert(port: Int, description: String, rules: Rule*)
                     (implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext): Unit = {
    val results = scrape(port, rules: _*).futureValue
    log.debug("Start: {}", description)
    results.foreach(_.assert())
    log.debug("End (Successful): {}", description)
  }

  object Rule {
    def create(definition: GaugeDefinition, assertion: String => _, labelValues: String*): Rule = {
      val name = definition.name
      val labels = definition.labels.zip(labelValues).map { case (k, v) => s"""$k="$v""""}.mkString(",")
      /*
       * Ex)
       * kafka_consumergroup_group_lag\{cluster_name="default",group="group-1-2",topic="topic-1-1",partition="0".*\}\s+(-?\d+\.\d+)
       * https://regex101.com/r/haxLfS/1
       */
      val regex = s"""$name\\{$labels.*\\}\\s+(-?\\d+\\.\\d+)""".r
      log.debug(s"Created regex: {}", regex.pattern.toString)
      Rule(regex, assertion)
    }
  }

  case class Rule(regex: Regex, assertion: String => _)

  case class Result(rule: Rule, groupResults: List[String]) {
    def assert(): Unit = {
      log.debug(s"Rule: ${rule.regex.toString}")
      groupResults.length shouldBe 1
      log.debug(s"Actual value is ${groupResults.head}")
      rule.assertion(groupResults.head)
    }
  }
}
