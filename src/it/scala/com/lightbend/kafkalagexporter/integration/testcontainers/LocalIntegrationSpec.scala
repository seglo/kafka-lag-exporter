package com.lightbend.kafkalagexporter.integration.testcontainers

import com.lightbend.kafkalagexporter.integration.{
  ExporterPorts,
  IntegrationSpec
}

class LocalIntegrationSpec
    extends LocalSpecBase(exporterPort = ExporterPorts.LocalIntegrationSpec)
    with IntegrationSpec
