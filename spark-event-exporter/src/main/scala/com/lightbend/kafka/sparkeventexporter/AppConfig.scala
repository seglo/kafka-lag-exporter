package com.lightbend.kafka.sparkeventexporter

import com.typesafe.config.Config

object AppConfig {
  def apply(config: Config): AppConfig = {
    val providedName = config.getString("provided-name")
    val port = config.getInt("port")
    val clientGroupId = config.getString("client-group-id")
    AppConfig(providedName, port, clientGroupId)
  }
}

final case class AppConfig(providedName: String, port: Int, clientGroupId: String) {
  override def toString(): String = {
    s"""
       |Provided name: $providedName
       |Prometheus metrics endpoint port: $port
       |Admin client consumer group id: $clientGroupId
     """.stripMargin
  }
}
