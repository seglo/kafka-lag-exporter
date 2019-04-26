/*
 * Copyright (C) 2016 - 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

object Domain {
  final case class TopicPartition(topic: String, partition: Int)
  final case class GroupTopicPartition(group: ConsumerGroup, topicPartition: TopicPartition)

  final case class ConsumerGroup(id: String, isSimpleGroup: Boolean, state: String, members: List[ConsumerGroupMember])
  final case class ConsumerGroupMember(clientId: String, consumerId: String, host: String, partitions: Set[TopicPartition])

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

    def all: Map[TopicPartition, LookupTable.Table] = tables
  }

  object TopicPartitionTable {
    def apply(limit: Int,
              tables: Map[TopicPartition, LookupTable.Table] = Map.empty[TopicPartition, LookupTable.Table]): TopicPartitionTable =
      new TopicPartitionTable(limit, tables)
  }
}
