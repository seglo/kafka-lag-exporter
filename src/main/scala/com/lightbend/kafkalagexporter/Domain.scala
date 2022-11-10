/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2022 Sean Glover <https://seanglover.com>
 */

package com.lightbend.kafkalagexporter

object Domain {
  final case class TopicPartition(topic: String, partition: Int)

  // Once upon a time I had a nested data structure for all these types, but I found that a flat datastructure
  // is much easier to work with, at the sacrifice of some duplication.
  final case class GroupTopicPartition(
      id: String,
      clientId: String,
      consumerId: String,
      host: String,
      topic: String,
      partition: Int
  ) {
    lazy val tp: TopicPartition = TopicPartition(topic, partition)
  }

  type GroupOffsets = Map[GroupTopicPartition, Option[LookupTable.Point]]

  object GroupOffsets {
    def apply(): GroupOffsets =
      Map.empty[GroupTopicPartition, Option[LookupTable.Point]]
    def apply(
        tuples: (GroupTopicPartition, Option[LookupTable.Point])*
    ): GroupOffsets = Map(tuples: _*)
  }

  type PartitionOffsets = Map[TopicPartition, LookupTable.Point]

  object PartitionOffsets {
    def apply(): PartitionOffsets = Map.empty[TopicPartition, LookupTable.Point]
    def apply(tuples: (TopicPartition, LookupTable.Point)*): PartitionOffsets =
      Map(tuples: _*)
  }

  class TopicPartitionTable private (
      var tables: Map[TopicPartition, LookupTable],
      config: ConsumerGroupCollector.CollectorConfig
  ) {
    def apply(tp: TopicPartition): LookupTable = {
      val clusterName = config.cluster.name
      tables = tables.updated(
        tp,
        tables.getOrElse(
          tp,
          LookupTable(clusterName, tp, config.lookupTableConfig)
        )
      )
      tables(tp)
    }

    def clear(evictedTps: List[TopicPartition]): Unit =
      tables = tables.view.filterKeys(tp => !evictedTps.contains(tp)).toMap

    def all: Map[TopicPartition, LookupTable] = tables
  }

  object TopicPartitionTable {
    def apply(
        tables: Map[TopicPartition, LookupTable] =
          Map.empty[TopicPartition, LookupTable],
        config: ConsumerGroupCollector.CollectorConfig
    ): TopicPartitionTable =
      new TopicPartitionTable(tables, config)
  }
}
