---
title: Design goals
headerTitle: Design goals
linkTitle: Design goals
description: Learn the design goals that drive the building of YugabyteDB.
aliases:
  - /preview/architecture/design-goals/
menu:
  preview:
    identifier: architecture-design-goals
    parent: architecture
    weight: 1105
type: docs
---

YugabyteDB was created to achieve a number of design goals.

## Consistency

YugabyteDB supports distributed transactions while offering strong consistency guarantees in the face of potential failures. 

For more information, see the following:

- [Achieving consistency with Raft consensus](../docdb-replication/replication/)
- [Fault tolerance and high availability](../core-functions/high-availability/)
- [Single-row linearizable transactions in YugabyteDB](../transactions/single-row-transactions/)
- [The architecture of distributed transactions](../transactions/distributed-txns/)

### CAP theorem and split-brain

In terms of the [CAP theorem](https://en.wikipedia.org/wiki/CAP_theorem), YugabyteDB is a consistent and partition-tolerant (CP) database that at the same time achieves very high availability. The architectural design of YugabyteDB is similar to Google Cloud Spanner, another CP system. The description of [Spanner](https://cloudplatform.googleblog.com/2017/02/inside-Cloud-Spanner-and-the-CAP-Theorem.html) is also applicable to YugabyteDB. The key takeaway is that no system provides 100% availability, so the pragmatic question is whether or not the system delivers sufficiently high availability that most users no longer have to be concerned about outages. For example, given that there are many sources of outages for an application, if YugabyteDB is an insignificant contributor to its downtime, then users are correct not to worry about it.

Split-brain is a computing scenario in which data and availability inconsistencies arise when a distributed system incurs a network partition. For YugabyteDB, when a network partition occurs, the remaining (majority for write acknowledgement purposes) RAFT group peers elect a new tablet leader. YugabyteDB implements _leader leases_, which ensures that a single tablet leader exists throughout the entire distributed system including when network partitions occur. Leader leases have a default value of two seconds, and can be configured to use a different value. This architecture ensures that YugabyteDB's distributed database is not susceptible to the split-brain condition.

### Single-row linearizability

YugabyteDB supports single-row linearizable writes. Linearizability is one of the strongest single-row consistency models, and implies that every operation appears to take place atomically and in some total linear order that is consistent with the real-time ordering of those operations. In other words, the following is expected to be true of operations on a single row:

- Operations can execute concurrently, but the state of the database at any point in time must appear to be the result of some totally ordered, sequential execution of operations.
- If operation A completes before operation B begins, then B should logically take effect after A.

### Multi-row ACID transactions

YugabyteDB supports multi-row transactions with three isolation levels: Serializable, Snapshot (also known as repeatable read), and Read Committed isolation.

- The [YSQL API](../../api/ysql/) supports Serializable, Snapshot (default), and Read Committed isolation using the PostgreSQL isolation level syntax of `SERIALIZABLE`, `REPEATABLE READ`, and `READ COMMITTED` respectively. For more details, see [YSQL vs. PostgreSQL isolation levels](#ysql-vs-postgresql-isolation -levels).
- The [YCQL API](../../api/ycql/dml_transaction/) supports only Snapshot isolation (default) using the `BEGIN TRANSACTION` syntax.

#### YSQL vs. PostgreSQL isolation levels

`READ COMMITTED` is the default isolation level in PostgreSQL and YSQL. If `yb_enable_read_committed_isolation=true`, `READ COMMITTED` is mapped to Read Committed of YugabyteDB's transactional layer (that is, a statement sees all rows that are committed before it begins). However, by default `yb_enable_read_committed_isolation=false` and in this case Read Committed of YugabyteDB's transactional layer maps to Snapshot isolation, thus making Snapshot isolation default in YSQL. 

Note that Read Committed support in YugabyteDB is currently in [Beta](/preview/faq/general/#what-is-the-definition-of-the-beta-feature-tag).

Refer to the [table of isolation levels](/preview/explore/transactions/isolation-levels/) to learn how YSQL isolation levels map to the levels defined by PostgreSQL.

## Query APIs

YugabyteDB does not reinvent data client APIs. The two supported APIs are YSQL and YCQL. They are compatible with existing APIs and extend their functionality.

### YSQL

[YSQL](../../api/ysql/) is a fully-relational SQL API that is wire-compatible with the SQL language in PostgreSQL. It is best fit for RDBMS workloads that need horizontal write scalability and global data distribution, while also using relational modeling features such as Joins, distributed transactions, and referential integrity (such as foreign keys). Note that YSQL [reuses the native query layer](https://blog.yugabyte.com/why-we-built-yugabytedb-by-reusing-the-postgresql-query-layer/) of the PostgreSQL open source project.

In addition:

- New changes to YSQL do not break existing PostgreSQL functionality.

- YSQL is designed with migrations to newer PostgreSQL versions over time as an explicit goal. This means that new features are implemented in a modular fashion in the YugabyteDB codebase to enable rapid integration with new PostgreSQL features as an ongoing process.

- YSQL supports wide SQL functionality, such as the following:
  - All data types
  - Built-in functions and expressions
  - Joins (inner join, outer join, full outer join, cross join, natural join)
  - Constraints (primary key, foreign key, unique, not null, check)
  - Secondary indexes (including multi-column and covering columns)
  - Distributed transactions (Serializable, Snapshot, and Read Committed Isolation)
  - Views
  - Stored procedures
  - Triggers

### YCQL

[YCQL](../../api/ycql/) is a semi-relational SQL API that is best suited for internet-scale OLTP and HTAP applications needing massive write scalability and fast queries. YCQL supports distributed transactions, strongly-consistent secondary indexes, and a native JSON column type. YCQL has its roots in the Cassandra Query Language.

For more information, see [The query layer overview](../query-layer/overview/).

## Performance

Written in C++ to ensure high performance and the ability to use large memory heaps (RAM) as an internal database cache, YugabyteDB is optimized primarily to run on SSDs and Non-Volatile Memory Express (NVMe) drives. YugabyteDB is designed with the following workload characteristics in mind:

- High write throughput
- High client concurrency
- High data density (total data set size per node)
- Ability to handle ever growing event data use cases

For more information, see [High performance in YugabyteDB](../docdb/performance/).

## Geographically distributed deployments

YugabyteDB is suitable for deployments where the nodes of the universe span across the following:

- Single zone
- Multiple zones
- Multiple regions that are geographically replicated
- Multiple clouds (both public and private clouds)

To provide functionality, a number of requirements must be met. For example, client drivers across various languages meet the following criteria:

- Cluster-awareness, with ability to seamlessly handle node failures.
- Topology-awareness, with ability to seamlessly route traffic.

## Cloud-native architecture

YugabyteDB is a cloud-native database, designed with a number of cloud-native principles in mind.

### Running on commodity hardware

- Ability to run on any public cloud or on-premises data center. This includes commodity hardware on bare metal machines, virtual machines, and containers.
- Not having hard external dependencies. For example, YugabyteDB does not rely on atomic clocks, but can use an atomic clock if available.

### Kubernetes-ready

YugabyteDB works natively in Kubernetes and other containerized environments as a stateful application.

### Open source

YugabyteDB is open source under the very permissive Apache 2.0 license.

## 

See also:

- [Overview of the architectural layers in YugabyteDB](../layered-architecture/)
- [DocDB architecture](../docdb/)
- [Transactions in DocDB](../transactions/)
- [Query layer design](../query-layer/)
- [Core functions](../core-functions/)
