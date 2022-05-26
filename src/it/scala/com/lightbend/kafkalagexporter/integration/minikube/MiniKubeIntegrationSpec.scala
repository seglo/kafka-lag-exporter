package com.lightbend.kafkalagexporter.integration.minikube

import com.lightbend.kafkalagexporter.integration.IntegrationSpec

import scala.concurrent.duration.DurationInt

class MiniKubeIntegrationSpec extends MinikubeSpecBase with IntegrationSpec {
  override implicit val patience: PatienceConfig =
    PatienceConfig(90.seconds, 2.second)
}
