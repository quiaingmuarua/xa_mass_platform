# Worker Runtime Redis Shape

Status: active Worker Binding, registered-member and Server identity Redis ABI.

## Namespace And Owners

All keys use the fixed `xa_mass:<scope>` base. Owners access only their own
keys; Server and Pacer use mechanical contracts rather than bypassing them.

```text
Kernel WorkerResourceCatalog  WorkerGroup directory and persistent Binding
Kernel WorkerScoreCore        registered members and scheduling Score
WorkerMatchingCatalog         Worker/Platform facts and Rules
Server Identity               external typed registration coordinates
Server Endpoint Directory     local defaults and ID -> URI; no Redis connection
```

## Kernel Worker Resources

```text
xa_mass:<scope>:worker:groups
  HASH field = workerGroupId
  value       = canonical WorkerGroupDescriptor JSON

xa_mass:<scope>:worker:bindings
  HASH field = workerId
  value       = {"endpointManagerId":"...","workerGroupId":"..."}
```

Binding is one global HASH. Its JSON has exactly two nonempty string fields;
canonical encoding sorts fields and escapes non-ASCII characters. Worker ID
comes from the HASH field and is not duplicated in JSON. Extra fields,
Properties, non-string or empty values and malformed JSON are rejected.
Read returns null for a corrupt record; registration returns INVALID without
overwriting it. WorkerGroup JSON and create-only equality remain unchanged.

`registerWorkers` runs a single bounded Lua over the Group and Binding HASHes,
checking Group existence once and processing 1..100 IDs. Missing Binding is
created. Existing same-Group Binding wins over the default and returns its
actual Endpoint; another Group conflicts. Group and Endpoint share one record,
so there is no separate Group-owner index. A second Score Owner call initializes
accepted missing members. These stages commit separately and retry fills gaps.

## Matching Facts

[Worker Matching](../../../worker_matching_jvm/README.md#persistent-catalog) owns
Worker facts, independent Platform Properties, create-only Task bindings and
derived Rule indexes. Every Task has a binding; fixed Handlers have no persisted
DSL definitions. One HMGET prepares at most 100 Task queries. Facts and their
enabled index projections update in one Matching Lua; Platform patch no longer
uses a client pre-read/CAS retry loop.

Prepare does not write facts. Score invalidation is a separate best-effort Owner
call after APPLIED writes. Index rebuild is startup-only and confined to enabled
Group namespaces. Matching storage, encoding and query budgets remain in its
Owner document instead of being duplicated in Kernel's storage contract.

## Worker Score

```text
xa_mass:<scope>:worker:score:<workerGroupId>
  ZSET member = workerId
  score       = opaque Worker score encoding
```

Only `WorkerScoreCore` interprets or mutates Score. `initializeRegisteredScores`
accepts 1..100 unique IDs and uses one Lua call with ZADD NX, returning just the
newly created ID set. The internal fixed cold coordinate is RECOVERY_RECHECK,
timeSlot=1, laneRank=0, dirty=0 (currently -200). It uses no TIME, ZSCORE,
ZMSCORE or confirmation read. Every existing Score value is preserved.

Membership is registration existence, not availability. `sampleRegisteredWorkerIds`
uses one bounded ZRANDMEMBER; Catalog then reads the matching Binding rows.
Binding reads and connection verification do not inspect Score. If Score
initialization fails or its response is lost, Binding remains and registration
throws an infrastructure error. A later call fills absent members.

## Server Identity

```text
xa_mass:<scope>:worker:identity:<workerGroupId>
  HASH field = typed registration-key output
  value       = server-issued workerId
```

Worker kind selects the existing registration-key algorithm, not a Redis key.
One bounded Lua creates or returns the entire UUID batch in input order.
Prepare checks Group and a configured default Endpoint before identity writes;
it does not subsequently reread identity to verify its own result.

## Scope Rebuild

This cutover has no runtime compatibility reads or data migration. Stop every
Server, Pacer, Adapter and Worker using the explicitly selected scope. Keep
configuration and source Properties as rebuild inputs. Clean only that exact
`xa_mass:<scope>:` prefix using SCAN plus UNLINK, then recreate Group, Worker and
Task resources in order. Never use KEYS, FLUSHDB or FLUSHALL. Do not mix old and
new binaries in one scope. Generated files retaining old Worker IDs may be
removed only from a reviewed explicit file list; Properties and Lab inventory
are not disposable identity caches.

The retired per-Group metadata, ID-owner and Server binding-bucket layouts have
no readers or writers. Re-registration after scope cleanup issues new Worker
IDs from the retained external registration coordinates. Proofs always use
independent `test_*` scopes and clean only their own exact prefix.

## Composition And Views

[Server Prepare](../../../server_jvm/README.md#workergroup-and-worker-preparation)
owns the ordered cross-owner use case. The
[Matching catalog](../../../worker_matching_jvm/README.md#persistent-catalog)
owns fact/Rule semantics, and the
[Worker resource model](../resource-model/worker-resource-model.md) owns Kernel
Binding and registration. Runtime views join independent Owner reads; they are neither new
truth records nor atomic snapshots across these keys.

## Guardrails

- Do not restore `worker:properties` or Properties inside Kernel metadata.
- Do not place Rules in Kernel Task JSON.
- Do not add Item Rule storage; Kernel TaskItems carry one selector,
  not Properties, derived query/ID mirrors or index scores.
- Do not use identity ownership for Worker discovery.
- Do not let Matching interpret Score or let Kernel interpret Properties.
- Do not add dual reads or migration aliases for the retired layout.
- Do not infer Adapter connectivity from Binding, facts or score.
