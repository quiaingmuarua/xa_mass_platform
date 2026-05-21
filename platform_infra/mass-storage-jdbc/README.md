# mass-storage-jdbc

Status: current JDBC control-plane storage owner README.

Use this file for JDBC storage work inside `platform_infra/`. Read the repo
root [AGENTS.md](../../AGENTS.md) and [../README.md](../README.md) first.

## Role

- owns JDBC task truth persistence
- owns JDBC worker registration persistence
- owns JDBC rule-definition persistence
- owns H2/PostgreSQL dialect wiring, migrations, and startup residue recovery

## Read This As Current Truth

Stable boundary:

- durable control-plane truth belongs here
- hot-path queue, lease, retry visibility, and backpressure truth do not
- high-volume task-message detail and attempt timelines do not
- if some detail clearly fits trace/audit better than either DB or runtime
  state, do not widen JDBC ownership

Current implementation facts:

- `JdbcTaskStorage` persists durable task truth but keeps `TaskMsg` and
  `TaskMsgAttempt` compatibility reads in-process
- `JdbcWorkerStorage` persists durable worker registration truth
  but keeps online/offline churn and worker locks
  in-process
- `JdbcWorkerStorage` does not own worker capability candidate indexes; those
  live in engine/runtime read models and should reach durable stores only via
  trace/event ingestion if historical query is needed
- `JdbcStorageRuntime` is currently more than a storage factory: it wires
  datasource, Flyway, adapter construction, and residue recovery, and it is the
  first file to re-check when boundary drift is suspected

Current implementation drift to keep explicit:

- `JdbcStorageRuntime` is still a convenience bundle for datasource, migration,
  adapter construction, and residue recovery; treat it as convergence work, not
  a long-term public extension point
- `JdbcTaskStorage` now owns a JDBC-local process-local compatibility
  projection instead of reusing the full in-memory task-storage backend, but
  that residue is still in-process and restart-volatile
- `JdbcWorkerStorage` now owns a JDBC-local process-local compatibility
  projection for worker/lock residue, but that residue is still
  in-process and restart-volatile
- some of that residue remains because JDBC is not the owner for high-volume
  message history or execution timelines; treat it as bounded compatibility
  state rather than widening JDBC ownership

Do not describe those drift points as target architecture. If they change,
update this README in the same change.

## Entry Files

- `src/main/java/com/xa/mass/storage/jdbc/JdbcStorageRuntime.java`
- `src/main/java/com/xa/mass/storage/jdbc/JdbcTaskStorage.java`
- `src/main/java/com/xa/mass/storage/jdbc/JdbcWorkerStorage.java`
- `src/main/java/com/xa/mass/storage/jdbc/JdbcRuntimeResidueRecovery.java`

## Fast Verification

Prefer these checks after changing this module:

```bash
./mvnw -q -pl platform_infra/mass-storage-jdbc -am test -Dtest=JdbcStorageH2Test -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl platform_infra/mass-storage-jdbc -am test -Dtest=JdbcStoragePostgresTest -Dsurefire.failIfNoSpecifiedTests=false
```

`JdbcStoragePostgresTest` requires a working Docker/Testcontainers environment.
When Docker is unavailable, treat H2 verification as the minimum local signal
and state that PostgreSQL verification was not run.

## Non-Goals

- no task lifecycle ownership
- no worker matching ownership
- no transport-specific protocol ownership
- no promotion of compatibility projections into durable hot-path truth
