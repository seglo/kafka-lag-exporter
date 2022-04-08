# Changelog

## [v0.6.8](https://github.com/seglo/kafka-lag-exporter/tree/v0.6.8) (2021-11-20)

[Full Changelog](https://github.com/seglo/kafka-lag-exporter/compare/v0.6.7...v0.6.8)

**Closed issues:**

- Build failed due to Bintray sunset [\#247](https://github.com/seglo/kafka-lag-exporter/issues/247)

**Merged pull requests:**

- Fix deployment [\#283](https://github.com/seglo/kafka-lag-exporter/pull/283) ([seglo](https://github.com/seglo))
- 0.6.8 release prep [\#282](https://github.com/seglo/kafka-lag-exporter/pull/282) ([seglo](https://github.com/seglo))
- 0.6.8 release prep [\#281](https://github.com/seglo/kafka-lag-exporter/pull/281) ([seglo](https://github.com/seglo))
- Add authorizer-properties to sample ACL command [\#275](https://github.com/seglo/kafka-lag-exporter/pull/275) ([seglo](https://github.com/seglo))
- Drop sbt-bintray [\#273](https://github.com/seglo/kafka-lag-exporter/pull/273) ([seglo](https://github.com/seglo))
- Axual/add configurable options [\#268](https://github.com/seglo/kafka-lag-exporter/pull/268) ([daniel-axual](https://github.com/daniel-axual))
- Update scalatest to 3.2.10 [\#261](https://github.com/seglo/kafka-lag-exporter/pull/261) ([scala-steward](https://github.com/scala-steward))
- Update scala-library to 2.12.15 [\#260](https://github.com/seglo/kafka-lag-exporter/pull/260) ([scala-steward](https://github.com/scala-steward))
- Common usecase for a servicemonitor is to add additional labels to it… [\#258](https://github.com/seglo/kafka-lag-exporter/pull/258) ([ryan-dyer-sp](https://github.com/ryan-dyer-sp))
- Update simpleclient, simpleclient\_hotspot, ... to 0.12.0 [\#257](https://github.com/seglo/kafka-lag-exporter/pull/257) ([scala-steward](https://github.com/scala-steward))
- Update akka-stream-alpakka-influxdb to 3.0.3 [\#253](https://github.com/seglo/kafka-lag-exporter/pull/253) ([scala-steward](https://github.com/scala-steward))
- feat: add support for different reporters in Helm chart [\#246](https://github.com/seglo/kafka-lag-exporter/pull/246) ([RoVernekar](https://github.com/RoVernekar))
- Use kafka\_consumergroup\_poll\_time\_ms metric as healthcheck [\#231](https://github.com/seglo/kafka-lag-exporter/pull/231) ([toshyak](https://github.com/toshyak))
- support adding annotations to created service account [\#227](https://github.com/seglo/kafka-lag-exporter/pull/227) ([uishon](https://github.com/uishon))
- Update influxdb, kafka to 1.15.3 [\#226](https://github.com/seglo/kafka-lag-exporter/pull/226) ([scala-steward](https://github.com/scala-steward))
- Update scala-java8-compat to 0.9.1 [\#218](https://github.com/seglo/kafka-lag-exporter/pull/218) ([scala-steward](https://github.com/scala-steward))
- Update mockito-scala to 1.0.10 [\#216](https://github.com/seglo/kafka-lag-exporter/pull/216) ([scala-steward](https://github.com/scala-steward))
- Update kafka to 2.5.1 [\#214](https://github.com/seglo/kafka-lag-exporter/pull/214) ([scala-steward](https://github.com/scala-steward))
- Update kubernetes-client, ... to 4.9.2 [\#212](https://github.com/seglo/kafka-lag-exporter/pull/212) ([scala-steward](https://github.com/scala-steward))
- Update sbt-header to 5.6.0 [\#211](https://github.com/seglo/kafka-lag-exporter/pull/211) ([scala-steward](https://github.com/scala-steward))
- Update akka-stream-kafka-testkit to 2.0.7 [\#209](https://github.com/seglo/kafka-lag-exporter/pull/209) ([scala-steward](https://github.com/scala-steward))
- Update akka-http to 10.1.14 [\#208](https://github.com/seglo/kafka-lag-exporter/pull/208) ([scala-steward](https://github.com/scala-steward))
- Update typesafe:config to 1.3.4 [\#206](https://github.com/seglo/kafka-lag-exporter/pull/206) ([scala-steward](https://github.com/scala-steward))
- Update sbt-release to 1.0.13 [\#204](https://github.com/seglo/kafka-lag-exporter/pull/204) ([scala-steward](https://github.com/scala-steward))


## Historical Change Log

*For new change log entries see [CHANGELOG.md](CHANGELOG.md)*

0.6.8

* _A lot up minor updates and version library bumps_

0.6.7

* Send globalClusterLabels as tags in InfluxDBSink [#200](https://github.com/lightbend/kafka-lag-exporter/pull/200) ([@lukaszkrawiec](https://github.com/lukaszkrawiec))

0.6.6

* Add support for Consumer Group blacklisting [#184](https://github.com/lightbend/kafka-lag-exporter/pull/184) ([@Manicben](https://github.com/Manicben))
* Add release steps to serve Helm Charts Repository on Github Pages [#183](https://github.com/lightbend/kafka-lag-exporter/pull/183) ([@akozich](https://github.com/akozich))
* Upgrade sbt from 1.2.6 to 1.4.3 to improve the metals support [#178](https://github.com/lightbend/kafka-lag-exporter/pull/178) ([@robsonpeixoto](https://github.com/robsonpeixoto))
* Automatically roll Deployment when ConfigMap change [#176](https://github.com/lightbend/kafka-lag-exporter/pull/176) ([@robsonpeixoto](https://github.com/robsonpeixoto))
* Support multiple instances and extra labels for service monitor [#171](https://github.com/lightbend/kafka-lag-exporter/pull/171) ([@ryan-dyer-sp](https://github.com/ryan-dyer-sp))
* Ability to extend and configure desired sink to report lag metrics, adding support to push lag metrics into InfluxDB as well [#157](https://github.com/lightbend/kafka-lag-exporter/pull/157) ([@hariprasad-k](https://github.com/hariprasad-k))

0.6.5

* Use `centos:8` docker base layer [#168](https://github.com/lightbend/kafka-lag-exporter/pull/168) ([@killuazhu](https://github.com/killuazhu))

0.6.4

* Bugfix: Filter Out NaN Values from Aggregate Metrics [#158](https://github.com/lightbend/kafka-lag-exporter/pull/158) ([@simoncaron](https://github.com/simoncaron))

0.6.3

* Configurable readiness and liveness probes in helm chart [#145](https://github.com/lightbend/kafka-lag-exporter/pull/145) ([@chelomontilla](https://github.com/chelomontilla))
* Swap embedded-kafka for testcontainers [#147](https://github.com/lightbend/kafka-lag-exporter/pull/147)
* Bugfix: Handle null offset in consumer group offset result [#149](https://github.com/lightbend/kafka-lag-exporter/pull/149)
* List Permissions required by Kafka Lag Exporter to run against a secured Kafka cluster [#152](https://github.com/lightbend/kafka-lag-exporter/pull/152)
* Bugfix: Evict all metrics for a cluster on collector stop or failure [#154](https://github.com/lightbend/kafka-lag-exporter/pull/154)

0.6.2

* Support init containers in helm chart [#135](https://github.com/lightbend/kafka-lag-exporter/pull/135) ([@terjesannum](https://github.com/terjesannum))
* Support consumer groups for which member information is unavailable [#128](https://github.com/lightbend/kafka-lag-exporter/pull/128) ([@lilyevsky](https://github.com/lilyevsky))

0.6.1

* Update to Apache Kafka 2.5.0. Resolves issue of "Invalid negative offset" for uninitizalized consumer groups [#120](https://github.com/lightbend/kafka-lag-exporter/issues/120)
* Graphite support [#105](https://github.com/lightbend/kafka-lag-exporter/pull/115) ([@yazgoo](https://github.com/yazgoo))

0.6.0

* Add Metadata poll timer metric `kafka_consumergroup_poll_time_ms` [#105](https://github.com/lightbend/kafka-lag-exporter/pull/105) ([@anbarasantr](https://github.com/anbarasantr))
* Bugfix: Bypass prediction when consumer group is caught up. Reported in [#111](https://github.com/lightbend/kafka-lag-exporter/issues/111) ([@rkrage](https://github.com/rkrage)).
* Publish Java App Packaging for non-Docker envs [#119](https://github.com/lightbend/kafka-lag-exporter/pull/119)

0.5.5

* Add kafka topic blacklist [#90](https://github.com/lightbend/kafka-lag-exporter/pull/90) ([@drrzmr](https://github.com/drrzmr))
* Add metric to represent a consumer group's total offset lag per topic [#93](https://github.com/lightbend/kafka-lag-exporter/pull/93) ([@dylanmei](https://github.com/dylanmei))
* Support specifying image digest and container securityContext [#95](https://github.com/lightbend/kafka-lag-exporter/pull/95) ([@terjesannum](https://github.com/terjesannum))
* Allow mounting extra configmaps in pod [#94](https://github.com/lightbend/kafka-lag-exporter/pull/94) ([@terjesannum](https://github.com/terjesannum))
* Bugfix: Fixed pod annotations support in helm chart [#91](https://github.com/lightbend/kafka-lag-exporter/pull/91) ([@terjesannum](https://github.com/terjesannum))
* Bugfix: Global label values [#82](https://github.com/lightbend/kafka-lag-exporter/pull/82) ([@anbarasantr](https://github.com/anbarasantr))
* Prometheus Operator Service Operator support [#85](ttps://github.com/lightbend/kafka-lag-exporter/pull/85) ([@abhishekjiitr](https://github.com/abhishekjiitr))
* Added kafka_partition_earliest_offset metric for determining the volume of offsets stored in Kafka. [#86](https://github.com/lightbend/kafka-lag-exporter/pull/86) ([@graphex](https://github.com/graphex))

0.5.4

* Bugfix: Accidentally released with local repo.

0.5.3

* Bugfix: Fix Helm Chart: Whitespace in Deployment.yaml [#77](https://github.com/lightbend/kafka-lag-exporter/pull/77) ([@abhishekjiitr](https://github.com/abhishekjiitr))
* Bugfix: Revert cluster labels (see discussion in [#78](https://github.com/lightbend/kafka-lag-exporter/pull/78)) [#79](https://github.com/lightbend/kafka-lag-exporter/pull/79)

0.5.2

* Implement consumer group whitelist [#75](https://github.com/lightbend/kafka-lag-exporter/pull/75)
* Allow whitelisting Kafka topics [#65](https://github.com/lightbend/kafka-lag-exporter/pull/65) ([@NeQuissimus](https://github.com/NeQuissimus))
* Omit service account generation when not using strimzi [#64](https://github.com/lightbend/kafka-lag-exporter/pull/64) ([@khorshuheng](https://github.com/khorshuheng))
* Adding support to control which prometheus metrics to expose [#62](https://github.com/lightbend/kafka-lag-exporter/pull/62) ([@khorshuheng](https://github.com/khorshuheng))
* Adds custom labels for every cluster [#61](https://github.com/lightbend/kafka-lag-exporter/pull/61) ([@anbarasantr](https://github.com/anbarasantr))
* Adding support for custom annotations on pods [#59](https://github.com/lightbend/kafka-lag-exporter/pull/59) ([@WarpRat](https://github.com/WarpRat))
* Allow Helm to quote Kafka client property values when necessary [#58](https://github.com/lightbend/kafka-lag-exporter/pull/58)

0.5.1

* Bugfix: Get commit offset for correct group topic partitions [#56](https://github.com/lightbend/kafka-lag-exporter/pull/56)

0.5.0

* Bugfix: Report NaN for group offset, lag, and time lag when no group offset returned. [#50](https://github.com/lightbend/kafka-lag-exporter/pull/50)
* Support arbitrary kafka client configuration. [#48](https://github.com/lightbend/kafka-lag-exporter/pull/48)
* Use ConfigMap to provide app and logging config. [#47](https://github.com/lightbend/kafka-lag-exporter/pull/47)
* Bugfix: Use lag offsets metric in lag offsets panel Grafana dashboard. [#39](https://github.com/lightbend/kafka-lag-exporter/pull/39/) ([@msravan](https://github.com/msravan))

0.4.3

* Update chart defaults to match app defaults.  Poll interval: 30s, Lookup table size: 60.

0.4.2

* Bugfix: Check for missing group topic partitions after collecting all group offsets. Regression bugfix. [#30](https://github.com/lightbend/kafka-lag-exporter/issues/30)
* Make simple polling logging `INFO` log level. Added `DEBUG` logging to show all offsets collected per poll for troubleshooting.

0.4.1

* Remove labels `state` and `is_simple_consumer` from group topic partition metrics
* Document metric endpoint filtering [#24](https://github.com/lightbend/kafka-lag-exporter/issues/24)
* Document standalone deployment mode [#22](https://github.com/lightbend/kafka-lag-exporter/issues/22)
* Evict metrics from endpoint when they're no longer tracked by Kafka [#25](https://github.com/lightbend/kafka-lag-exporter/issues/25)
* Support clusters with TLS and SASL [#21](https://github.com/lightbend/kafka-lag-exporter/pull/21)

0.4.0

* Open Sourced! 🎆 [#17](https://github.com/lightbend/kafka-lag-exporter/issues/17)
* Add Integration tests using Embedded Kafka [#11](https://github.com/lightbend/kafka-lag-exporter/issues/11)
* Replace lag in time implementation with interpolation table implementation [#5](https://github.com/lightbend/kafka-lag-exporter/issues/5)
* Removed `spark-event-exporter`.  See the [`spark-committer`](https://github.com/lightbend/spark-committer) GitHub
  project to commit offsets in Spark Structured Streaming back to Kafka. [#9](https://github.com/lightbend/kafka-lag-exporter/issues/9)
* Implement backoff strategy for Kafka connections in Kafka Lag Exporter [#6](https://github.com/lightbend/kafka-lag-exporter/issues/6)
* Travis build [#7](https://github.com/lightbend/kafka-lag-exporter/issues/7)
* Update docs [#14](https://github.com/lightbend/kafka-lag-exporter/issues/14)
* Update Grafana dashboard
* Licensing headers
* Script release process

0.3.6

* Add `kafka-client-timeout` config.
* Tune retry and timeout logic of Kafka admin client and consumer
* Use backoff strategy restarting offset collection logic when transient runtime exceptions are encountered
* Terminate when Prometheus HTTP server can't start (i.e. port can't be bound)

0.3.1

* Default partition to 0 (instead of omitting it from being reported) when a consumer group returns no offset for a
  group partition
* Use `akkaSource` for actor path in logging

0.3.0

* Bugfix: Parse `poll-interval` in seconds
* Rename metric from `kafka_consumergroup_latest_offset` to `kafka_partition_latest_offset`
* Use JVM 8 experimental cgroup memory awareness flags when running exporter in container
* Use snakecase for metric label names
* Sample Grafana Dashboard

0.2.0

* Strimzi cluster auto discovery

0.1.0

* Initial release

\* *This Changelog was automatically generated by [github_changelog_generator](https://github.com/github-changelog-generator/github-changelog-generator)*
