package com.lightbend.kafkalagexporter

import org.scalatest._

class StrimziClusterScannerSpec extends FlatSpec with Matchers {

  it should "scan" in {
    val scanner = new StrimziClusterScannerFabric8
    val stuff = scanner.scan()
    assert(true)
  }

  it should "watch" in {
    val scanner = new StrimziClusterScannerFabric8
    val stuff = scanner.watch()
    assert(true)
  }

}
