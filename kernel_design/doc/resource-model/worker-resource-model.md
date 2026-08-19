# Worker Resource Model

Status: active Kernel owner contract; Python executable spec is the mechanism
oracle and selected JVM Redis providers implement the production write path.

## Core Identity

```text
WorkerGroup = one plugin/package capability bucket
            + one scheduling namespace

Worker      = one logical execution slot in exactly one WorkerGroup
```

A WorkerGroup is close to a deployable plugin or package identity and provides
a stable bucket for Workers with broadly shared capabilities. Its `eventCodes`
are replaceable catalog metadata used for display and future Server
recommendation. They may lag the definitions currently running in Workers and
are not Kernel scheduling truth. A WorkerGroup is not an Adapter identity,
tenant, or transport connection.

Task admission fixes one `workerGroupId`. Candidate rules only narrow Workers
inside that group. `eventCode` is delivered to the chosen Worker and resolved
there; Kernel dispatch does not validate it against WorkerGroup catalog
metadata.

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

An explicit WorkerGroup upsert atomically replaces both `attributes` and
`eventCodes`. Identical content is a no-op. Updating this directory summary
does not move Workers, change scores, or assert that running Worker definitions
already match it.

WorkerGroup does not declare matching indexes, providers, or supported
operators. Candidate sources remain separate scheduling mechanisms; matching
only reads canonical properties for the bounded Worker IDs already supplied by
one of those sources.

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

`WorkerRuntime.upsert_worker` receives this declaration from the Server Bind
control path after registration coordinates are validated and an endpoint is
persisted. First upsert creates the
Worker metadata, stores the complete `workerProperties` snapshot, and
initializes a missing score. A compatible repeated Bind replaces the complete
Worker-owned snapshot. Upsert is a resource refresh operation, not durable
connectivity or activation evidence. The Platform cannot patch the
Worker-owned snapshot.

Bind is the only external refresh path for canonical Worker Properties. A
running Worker may answer an explicit properties snapshot Command for
observation, but Adapter-local observation does not write this owner. Changes
in the Host provider reach canonical Properties only on a later explicit
Worker start and Bind.

The external Worker identity registry is not part of this Kernel contract. It
extracts `workerProperties.clientWorkerKey` and maps it with `workerGroupId` to
a long-lived Worker ID. A separate Server Binding registry maps that Worker ID
to an Endpoint Manager. Kernel receives only the resulting declaration and
does not interpret the client key, public endpoint URI, or connection check.

Worker identity coordinates are immutable:

```text
workerId
workerGroupId
endpointManagerId
```

The first accepted WorkerId also fixes its WorkerGroup owner. Conflicting
identity declarations do not alter the descriptor or score.

Kernel resource consumers that receive Worker-id-only evidence may resolve one
bounded explicit Worker set through:

```python
get_worker_group_ids(worker_ids) -> worker_id/worker_group_id map
```

The lookup reads immutable ownership established by Worker upsert. Missing
entries remain missing; it does not discover Workers, scan Groups, prove that a
descriptor or score exists, or expose a Transport requirement. In particular,
Worker and Adapter messages continue to use the globally unique `workerId`
without carrying `workerGroupId` for routing.

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
field. Worker upsert preserves the current Platform snapshot because metadata
and Worker Properties are stored independently. Platform patch uses a bounded
compare-and-set on metadata; Worker snapshot replacement writes only the
Worker Properties row, so the two source writers cannot overwrite each other.

Properties are intended for bounded views, diagnostics, and low-frequency
matching facts. They are not transport reachability, score, lease, assignment,
or execution truth.

## Rule Matching

Matcher context is fixed:

```json
{
  "workerId": "worker-1",
  "worker": {"arch": "arm64", "region": "cn-east"},
  "platform": {"pool": "batch", "load": "42"}
}
```

Only the first dot separates domain from property name. Supported coordinates
are `workerId`, `worker.*`, and `platform.*`. The removed `index.*` namespace
is rejected as an invalid allocation rule and never falls back to a same-named
canonical property.

### DIRECT

For an empty rule, DIRECT uses one bounded due-HOT score query in the explicit
WorkerGroup. For a non-empty rule, `workerId $eq/$equal/$in` produces the
bounded request-local Worker-id set; without it, the rule fails closed. Other
`worker.*` and `platform.*` conditions filter only that bounded set and do not
generate candidates. Explicit-ID rules are evaluated before score observation
and every leased result is fully rematched.

DIRECT does not use `CandidateWorkerCache`, scan Worker descriptors, or fall
back to PRECOMPUTED acquisition. A future low-cost candidate source is a
separate mechanism addition, not an expansion of canonical property reads.

One DIRECT acquisition round admits at most 100 unique WorkerIds across all
Item candidates in `(priority, candidateId)` order. A WorkerId already admitted
may be reused by a later Item without consuming the budget again. New ids after
the budget is exhausted wait for a later round; they do not trigger a scan or
fallback.

### PRECOMPUTED

PRECOMPUTED consumes bounded WorkerIds from `CandidateWorkerCache`, validates
the exact score fences, and rematches the complete Task rule.

For each rule field:

```text
worker.*     -> read workerProperties
platform.*   -> read platformProperties
workerId     -> built-in identity
```

The matcher builds one context for each existing candidate and evaluates the
complete rule in memory. Invalid stored rules produce one safe aggregate
diagnostic per matcher call; the log does not contain rule or property values.

Rules currently contain only required conditions. Priority or preference
terms are a future rule-model change and must not be simulated by unsupported
DIRECT predicates.

## Lifecycle Boundaries

Resource metadata, scheduling serviceability, binding, connectivity, and
execution remain separate axes:

```text
Resource        WorkerGroup plus Worker metadata/properties
Scheduling      Worker score polarity, lease, recovery, dirty fence
Binding         immutable endpointManagerId route declaration
Registration    external client coordinate to long-lived workerId
Bind            persisted endpoint route plus Kernel Worker upsert
Connectivity    Adapter-local active connection evidence
Execution       TaskItem claim and Worker result evidence
```

Worker upsert initializes a missing HOT score, including retry recovery after a
partial first Bind. If a score already exists, upsert preserves its polarity,
coordinate, dirty bit, and lease exactly. Property writes do not read or mutate
score or release a lease. Attribute changes do not revoke an
already claimed Item or a command already delivered to a Worker.

Physical Worker removal, disable/drain, ordered update versions, new bounded
candidate sources, numeric/range matching, and preference ranking remain
separate milestones.

## Owner Guardrails

- Do not let Platform writes modify `workerProperties`.
- Do not accept `index.*` as a matching coordinate.
- Do not use Properties as connectivity evidence.
- Do not scan descriptors to satisfy DIRECT rules.
- Do not infer physical truth from the latest canonical Properties snapshot.
- Do not put score, lease, connection, or Task assignment state in a Worker
  descriptor.
