/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
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
                                        partition: Int) {
    lazy val tp: TopicPartition = TopicPartition(topic, partition)
  }

  type GroupOffsets = Map[GroupTopicPartition, LookupTable.Point]

  object GroupOffsets {
    def apply(): GroupOffsets = Map.empty[GroupTopicPartition, LookupTable.Point]
  }

  type PartitionOffsets = Map[TopicPartition, LookupTable.Point]

  object PartitionOffsets {
    def apply(): PartitionOffsets = Map.empty[TopicPartition, LookupTable.Point]
  }

  class TopicPartitionTable private(limit: Int, var tables: Map[TopicPartition, LookupTable.Table]) {
    def apply(tp: TopicPartition): LookupTable.Table = {
      tables = tables.updated(tp, tables.getOrElse(tp, LookupTable.Table(limit)))
      tables(tp)
    }

    def clear(evictedTps: List[TopicPartition]): Unit = tables.filterKeys(tp => evictedTps.contains(tp))

    def all: Map[TopicPartition, LookupTable.Table] = tables
  }

  object TopicPartitionTable {
    def apply(limit: Int,
              tables: Map[TopicPartition, LookupTable.Table] = Map.empty[TopicPartition, LookupTable.Table]): TopicPartitionTable =
      new TopicPartitionTable(limit, tables)
  }
}
