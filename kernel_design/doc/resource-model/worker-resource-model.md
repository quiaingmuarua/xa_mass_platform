# Worker Resource Model

Status: active Kernel owner contract; Python executable spec is the mechanism
oracle and selected JVM Redis providers implement the production write path.

## Core Identity

```text
WorkerGroup = one built Worker package/capability contract
            + one scheduling namespace

Worker      = one logical execution slot in exactly one WorkerGroup
```

A WorkerGroup is close to a deployable module or package identity. Its
`eventCodes` declare the common capabilities expected from every Worker in the
group. It is not an operator-created pool, Adapter identity, tenant, transport
connection, or display-only grouping.

Task admission fixes one `workerGroupId`. Candidate rules only narrow Workers
inside that group. `eventCode` is delivered to the chosen Worker and resolved
there; Kernel dispatch does not revalidate event capability for every Item.

One `workerId` is one scheduler-visible serial slot. A process that executes
multiple Items concurrently exposes multiple WorkerIds. Connection state is a
delivery concern and does not change this identity.

## WorkerGroupDescriptor

```python
WorkerGroupDescriptor(
    worker_group_id: str,
    attributes: Mapping[str, JsonValue],
    event_codes: frozenset[str],
)
```

`eventCodes` is immutable after first creation. Changing the built capability
contract requires a different WorkerGroup identity.

`attributes` is a complete replacement value on a successful repeat upsert.
WorkerGroup does not declare indexes, index providers, or supported
operators. Property indexes are process-level scheduling projections and can
be added or removed from startup configuration without changing the package
identity.

## Two Property Sources

Worker metadata has exactly two source domains:

```text
worker.*    Worker-reported facts
platform.*  Platform-owned facts
```

They are ownership domains, not freshness claims.

### Worker Properties

```python
WorkerDeclaration(
    worker_id: str,
    worker_group_id: str,
    endpoint_manager_id: str,
    worker_properties: Mapping[str, JsonValue],
)
```

Worker registration supplies the initial `workerProperties` snapshot. A
compatible repeated registration is a no-op and cannot overwrite either
property snapshot. `WorkerRuntime.update_worker_properties` is the separate
complete-replacement operation for the Worker-owned snapshot. Registration and
property update are resource operations, not connectivity or activation
evidence. The Platform cannot patch the Worker-owned snapshot.

Worker identity coordinates are immutable:

```text
workerId
workerGroupId
endpointManagerId
```

The first accepted WorkerId also fixes its WorkerGroup owner. Conflicting
identity declarations do not alter the descriptor or score.

### Platform Properties

The query projection is:

```python
WorkerDescriptor(
    worker_id: str,
    worker_group_id: str,
    endpoint_manager_id: str,
    worker_properties: Mapping[str, JsonValue],
    platform_properties: Mapping[str, JsonValue],
)
```

`platformProperties` is patched by field. A `null` patch value deletes the
field. Worker property update preserves the current Platform snapshot. Both
source writers compare the observed descriptor value before replacement and
retry a bounded number of times, so concurrent updates cannot discard the
other source's accepted snapshot.

Properties are intended for bounded views, diagnostics, and low-frequency
matching facts. They are not transport reachability, score, lease, assignment,
or execution truth.

## Property Index

The property index is independent from both snapshots:

```text
Properties       = current owner snapshots for bounded reads and views
Property Index   = last projection accepted by Kernel for scheduling lookup
```

An indexed value may also appear in a snapshot, but no operation writes both
automatically. Callers explicitly choose whether to update a snapshot, an
index, or both. An index-only Platform calculation therefore need not pollute
`platformProperties`.

Each configured field has one `WorkerPropertyIndex` implementation:

```python
update(workerGroupId, workerId, value)
load(workerGroupId, boundedWorkerIds) -> workerId/value map
```

`WorkerPropertyIndexRuntime` is only the Kernel owner Router:

```python
update_indexed_properties(workerGroupId, workerId, updates)
load_indexed_property_values(
    workerGroupId,
    indexField,
    boundedWorkerIds,
)
```

Startup composition supplies one immutable map such as:

```text
index.worker.region -> Redis HASH point projection
index.platform.pool -> Redis HASH point projection
```

The key is the complete index identity. Its suffix is opaque: the
`index.worker.region` projection is not authorized by, copied from, or kept in
sync with `worker.region`. Update requests use these qualified keys directly;
`null` removes one projection. The Router validates Worker ownership, then
routes each field independently. Reads accept only an explicit bounded
Worker-id set and return a sparse value map. They do not discover candidates
or interpret allocation operators. Missing implementations and provider
failures remain distinguishable from a missing value.

The current Redis HASH provider supports JSON-compatible point values. It is a
projection store, not a candidate query engine.

Index values have last-applied semantics. They do not carry a revision,
observation timestamp, or claim of physical real-time truth. If a future use
case requires execution-time certainty, the Worker may recheck it; scheduling
does not do that in this slice.

## Rule Matching

Matcher context is fixed:

```json
{
  "workerId": "worker-1",
  "worker": {"arch": "arm64", "region": "cn-east"},
  "platform": {"pool": "batch", "load": "42"},
  "index": {"worker.region": "cn-east"}
}
```

Only the first dot separates domain from property name. An indexed field named
`index.worker.location.region` addresses index key `worker.location.region`.
`worker.region` and `index.worker.region` are independent conditions and never
fall back to one another.

### TARGETED

TARGETED currently requires `workerId $eq/$equal/$in`. This identity condition
produces the bounded, request-local Worker-id set. Other `worker.*` and
`platform.*` conditions do not generate or filter candidates through indexes;
they are evaluated by the complete matcher before score observation and again
after an exact lease.

TARGETED does not use `CandidateWorkerCache`, scan Worker descriptors, compose
multiple indexes, or fall back to PRECOMPUTED acquisition. A future low-cost
candidate source is a separate mechanism addition, not an expansion of the
Property Index contract.

One TARGETED acquisition round admits at most 100 unique WorkerIds across all
Item candidates in `(priority, candidateId)` order. A WorkerId already admitted
may be reused by a later Item without consuming the budget again. New ids after
the budget is exhausted wait for a later round; they do not trigger a scan or
fallback.

### PRECOMPUTED

PRECOMPUTED consumes bounded WorkerIds from `CandidateWorkerCache`, validates
the exact score fences, and rematches the complete Task rule.

For each rule field:

```text
index.*      -> point-load through Property Index.load
worker.*     -> read workerProperties
platform.*   -> read platformProperties
workerId     -> built-in identity
```

The matcher builds one context for each existing candidate and evaluates the
complete rule in memory. If an explicit index field has no current value, it
never falls back to a same-named snapshot value. A missing implementation or
provider failure makes that bounded matching round fail closed. Invalid stored
rules and index read failures produce one safe aggregate diagnostic per matcher
call; the log does not contain rule or property values.

Rules currently contain only required conditions. Priority or preference
terms are a future rule-model change and must not be simulated by unindexed
TARGETED predicates.

## Lifecycle Boundaries

Resource metadata, scheduling serviceability, binding, connectivity, and
execution remain separate axes:

```text
Resource        WorkerGroup and Worker descriptors
Scheduling      Worker score polarity, lease, recovery, dirty fence
Binding         immutable endpointManagerId route declaration
Connectivity    Adapter-local active connection evidence
Execution       TaskItem claim and Worker result evidence
```

Worker registration initializes a missing HOT score, including retry recovery
after a partial first registration. If a score already exists, registration
preserves its polarity, coordinate, dirty bit, and lease exactly. Property and
index updates do not read or mutate score or release a lease. Attribute changes do not revoke an
already claimed Item or a command already delivered to a Worker.

Physical Worker removal, disable/drain, index residue cleanup, ordered update
versions, numeric/range providers, and preference ranking remain separate
milestones.

## Owner Guardrails

- Do not let Platform writes modify `workerProperties`.
- Do not auto-project Properties into indexes.
- Do not expose index-only values through Runtime View.
- Do not use Properties or indexes as connectivity evidence.
- Do not make index update failure roll back Worker registration, Dispatch, or
  ResultRouting.
- Do not scan descriptors to satisfy TARGETED rules.
- Do not infer physical truth from the latest accepted scheduling projection.
- Do not put score, lease, connection, or Task assignment state in a Worker
  descriptor.
