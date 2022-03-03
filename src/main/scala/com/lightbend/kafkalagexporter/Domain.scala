/*
 * Copyright (C) 2019-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

object Domain {
  final case class TopicPartition(topic: String, partition: Int)
  final case class Point(offset: Long, time: Long)

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

  type GroupOffsets = Map[GroupTopicPartition, Option[Point]]

  object GroupOffsets {
    def apply(): GroupOffsets = Map.empty[GroupTopicPartition, Option[Point]]
    def apply(tuples: (GroupTopicPartition, Option[Point])*): GroupOffsets = Map(tuples: _*)
  }

  type PartitionOffsets = Map[TopicPartition, Point]

  object PartitionOffsets {
    def apply(): PartitionOffsets = Map.empty[TopicPartition, Point]
    def apply(tuples: (TopicPartition, Point)*): PartitionOffsets = Map(tuples: _*)
  }

  class TopicPartitionTable private(limit: Int, var tables: Map[TopicPartition, LookupTable.Table]) {
    def apply(tp: TopicPartition): LookupTable.Table = {
      tables = tables.updated(tp, tables.getOrElse(tp, LookupTable.Table(tp, limit)))
      tables(tp)
    }

    def clear(evictedTps: List[TopicPartition]): Unit =
      tables = tables.filterKeys(tp => !evictedTps.contains(tp))

    def all: Map[TopicPartition, LookupTable.Table] = tables
  }

  object TopicPartitionTable {
    def apply(limit: Int,
              tables: Map[TopicPartition, LookupTable.Table] = Map.empty[TopicPartition, LookupTable.Table]): TopicPartitionTable =
      new TopicPartitionTable(limit, tables)
  }
}
