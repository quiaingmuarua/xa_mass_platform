# mass-storage-jdbc

Status: current JDBC control-plane storage owner README.

Use this file for JDBC storage work inside `platform_infra/`. Read the repo
root [AGENTS.md](../../AGENTS.md) and [../README.md](../README.md) first.

## Role

- owns JDBC task truth persistence
- owns JDBC rule-definition persistence
- owns JDBC project/event catalog metadata persistence
- owns H2/PostgreSQL dialect wiring and migrations
- does not own worker runtime registry, worker locks, dispatch gates, or
  worker registration churn

## Read This As Current Truth

Stable boundary:

- durable control-plane truth belongs here
- worker runtime registry truth does not belong here; worker history/operator
  query should flow through trace/audit ingestion instead of hot-path CRUD
- hot-path queue, lease, retry visibility, and backpressure truth do not
- high-volume task-message detail and attempt timelines do not
- if some detail clearly fits trace/audit better than either DB or runtime
  state, do not widen JDBC ownership

Current implementation facts:

- `JdbcTaskShellStore` persists durable task shell truth but keeps `TaskMsg` and
  `TaskMsgAttempt` compatibility reads in-process
- `JdbcCatalogMetadataStore` persists restart-readable project/event catalog
  metadata through the shared `CatalogMetadataStore` contract; it does not
  persist WorkerGroup, adapter-node, worker presence, heartbeat, or runtime
  topology truth
- `JdbcStorageRuntime` is currently more than a storage factory: it wires
  datasource, Flyway, and adapter construction, and it is the
  first file to re-check when boundary drift is suspected

Current implementation drift to keep explicit:

- `JdbcStorageRuntime` is still a convenience bundle for datasource, migration,
  and adapter construction; treat it as convergence work, not a long-term
  public extension point
- `JdbcTaskShellStore` now owns a JDBC-local process-local compatibility
  projection instead of reusing the full in-memory task-storage backend, but
  that residue is still in-process and restart-volatile
- worker runtime storage is intentionally not provided by this module; use the
  runtime worker registry backend selected by engine/transport assembly

Do not describe those drift points as target architecture. If they change,
update this README in the same change.

## Entry Files

- `src/main/java/com/xa/mass/storage/jdbc/JdbcStorageRuntime.java`
- `src/main/java/com/xa/mass/storage/jdbc/JdbcTaskShellStore.java`
- `src/main/java/com/xa/mass/storage/jdbc/JdbcRuleStorage.java`
- `src/main/java/com/xa/mass/storage/jdbc/JdbcCatalogMetadataStore.java`
- `src/main/resources/db/migration/control-plane/V4__create_catalog_tables.sql`

## Fast Verification

Prefer these checks after changing this module:

```bash
./mvnw -q -pl platform_infra/mass-storage-jdbc -am test -Dtest=JdbcStorageH2Test -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl platform_infra/mass-storage-jdbc -am test -Dtest=JdbcStoragePostgresTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl platform_infra/mass-storage-jdbc -am test -Dtest=JdbcH2CatalogMetadataStoreContractTest,JdbcSQLiteCatalogMetadataStoreContractTest -Dsurefire.failIfNoSpecifiedTests=false
```

`JdbcStoragePostgresTest` requires a working Docker/Testcontainers environment.
When Docker is unavailable, treat H2 verification as the minimum local signal
and state that PostgreSQL verification was not run.

## Non-Goals

- no task lifecycle ownership
- no worker matching ownership
- no transport-specific protocol ownership
- no promotion of compatibility projections into durable hot-path truth
