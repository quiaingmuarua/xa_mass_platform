# Worker Runtime Redis Shape

Status: active Kernel Redis ABI; Python executable spec is the mechanism
oracle and JVM providers must read and write the same bytes.

## Namespace And Owners

```text
wr:{prefix}:...
```

`prefix` is a deployment namespace. `workerGroupId` is the Worker home bucket
and score partition. Redis structures are owned separately:

```text
WorkerResourceCatalog      WorkerGroup descriptors plus Worker metadata/properties
WorkerRuntime              Worker metadata establishment and snapshot refresh
WorkerPropertyIndexRuntime property projection HASH values
WorkerScoreCore            Worker scheduling score ZSET
```

Delivery mailboxes and Worker results use their own `wd:` and `rr:`
namespaces. Task assignment, connection state, and execution truth never enter
the Worker resource keys.

## WorkerGroup Descriptors

```text
wr:{prefix}:groups
  HASH field = workerGroupId
  value       = canonical WorkerGroupDescriptor JSON
```

Example:

```json
{
  "attributes": {"runtime": "java"},
  "eventCodes": ["telecom.phone.inspect"],
  "workerGroupId": "phone-workers"
}
```

Keys are emitted in stable order, sets are sorted, and JSON is compact.

Upsert behavior:

```text
HSETNX establishes WorkerGroup
identical descriptor -> NOOP
changed attributes and/or eventCodes -> exact CAS full replacement
stored descriptor identity mismatch -> CONFLICT
damaged stored descriptor -> INVALID
repeated CAS contention -> STALE
```

`workerGroupId` is the stable HASH field and scheduling partition identity.
`eventCodes` is replaceable control-plane catalog metadata; it is not read by
Matcher or Dispatch and does not assert the Handler set currently installed on
every Worker.

## Worker Metadata And Properties

```text
wr:{prefix}:worker-metadata:{workerGroupId}
  HASH field = workerId
  value       = canonical WorkerMetadata JSON

wr:{prefix}:worker-properties:{workerGroupId}
  HASH field = workerId
  value       = canonical workerProperties JSON object

wr:{prefix}:worker-id-owners
  HASH field = workerId
  value       = workerGroupId
```

Example metadata:

```json
{
  "endpointManagerId": "system-polling",
  "platformProperties": {"poolLabel": "default"},
  "workerGroupId": "phone-workers",
  "workerId": "phone-worker-1"
}
```

Example Worker Properties row:

```json
{"arch":"arm64","region":"cn-east"}
```

`worker-id-owners` is the immutable identity fence and backs only the bounded
explicit-ID `get_worker_group_ids` owner read. It is not a global query catalog,
Worker discovery source, or Transport routing structure.

Worker upsert intentionally remains multi-stage:

```text
require WorkerGroup descriptor
-> HSETNX workerId owner
-> establish or validate immutable Worker metadata coordinates
-> replace the complete workerProperties row
-> initialize HOT_ACQUIRE only when Worker score is missing
-> preserve every existing Worker score byte-for-byte
```

Interrupted stages converge on retry. The operation is not wrapped in a
cross-key transaction.

Repeated compatible upsert writes the latest complete `workerProperties` row
and never accesses an existing score. Platform property patch performs a
bounded metadata read, applies field updates, removes `null` values, and CAS
writes one canonical metadata row. Because the two property sources use
different HASHes, Worker snapshot replacement and Platform patch cannot
restore a stale value owned by the other source. Platform patch may return
`STALE` under sustained metadata contention and never changes
`workerProperties`, score, or property indexes.

The Server-owned identity and endpoint Binding registries use a separate
namespace:

```text
wi:{prefix}:worker-registrations:{workerGroupId}
  HASH field = clientWorkerKey
  value       = canonical UUID workerId

wi:{prefix}:worker-bindings:{00..ff}
  HASH field = canonical UUID workerId
  value       = endpointManagerId
```

Registration establishes long-lived external identity. Binding establishes the
persistent delivery route used to project `endpointManagerId` into Kernel
Worker metadata. The 256 bucket suffix is the first SHA-256 byte of the
canonical workerId and only limits HASH size; the existing `{prefix}` hash tag
remains unchanged. Neither registry is a Kernel owner key, candidate catalog,
authentication record, or connectivity truth.

## Redis HASH Property Projection

This is the private Redis ABI of the first concrete
`WorkerPropertyIndex` provider. The Kernel owner contract does not prescribe
these keys or value encoding. One shared provider creates one projection
instance per explicitly configured `index.*` identity.

Point values:

```text
wr:{prefix}:property-index:{workerGroupId}:{propertyField}:values
  HASH field = workerId
  value       = canonical {"value": <JSON value>}
```

`propertyField` is the explicit index identity, for example
`index.worker.region` or `index.platform.pool`. Java and Python use the same
wrapper and compact JSON shape.

One accepted field update performs:

```text
null  -> HDEL point value
value -> HSET canonical point value
```

Writes are last-applied and intentionally not revisioned. Failure does not roll
back Worker resource, Dispatch, or ResultRouting truth.

Update requests use the complete `index.*` identity. The owner Router verifies
the Worker belongs to the requested WorkerGroup and invokes the matching
startup-configured index. Results are returned per field. Bounded reads use
one `HMGET` for explicit WorkerIds and return only present values. They do not
evaluate operators, scan the HASH, discover candidates, or maintain reverse
membership. Scheduling uses these values only for complete in-memory rule
matching after a separate candidate source has supplied bounded WorkerIds.

Removing an entry from process configuration does not delete this HASH. While
the field is disabled it cannot be read or updated through the owner Router;
re-enabling the same field resumes access to its last successfully written
projection.

## Worker Score

```text
wr:{prefix}:score:{workerGroupId}
  ZSET member = workerId
  score       = opaque Worker score encoding
```

Only `WorkerScoreCore` interprets or mutates score values. Positive values are
HOT_ACQUIRE scheduling-serviceability; negative values are RECOVERY_RECHECK.
Time, lane rank, dirty fence, exact compare-and-set, lease, hold, recovery, and
release semantics are defined in
[Worker Score Band Scheduling](../scheduling/worker-score-band-scheduling.md).

Java currently implements score get, fixed-zero-lane initialization,
same-polarity rewrite, and release operations. Worker upsert calls only
get/initialize. Python remains owner of scheduling score operations, Pacers,
recovery, and ResultRouting disposition.

Resource or property-index writes do not require a Worker lease and do not
mutate an existing score. A future explicit lifecycle operation must own any
RECOVERY_RECHECK-to-HOT_ACQUIRE transition.

## Bounded Reads

Catalog reads are caller-bounded:

```text
get_worker_group_descriptors(explicit WorkerGroupIds)
get_worker_descriptors(one WorkerGroupId, explicit WorkerIds)
sample_worker_descriptors(one WorkerGroupId, sampleLimit <= 100)
```

Worker preview samples metadata with one positive-count
`HRANDFIELD ... WITHVALUES`, then loads the corresponding properties rows. It
is an unordered incomplete sample with no cursor, total, stability, or
completeness claim. A missing or unreadable row on either side is returned as
`workerId -> None`.

Runtime View exposes only WorkerGroup descriptors and Worker descriptor
Properties. It does not join property indexes, score, mailbox, connection, or
Task assignment data.

## Consistency And Guardrails

Strong stale-state protection is reserved for score compare-and-set and
identity ownership. Descriptor replacement and property projections use
bounded eventual convergence.

- Do not add legacy JSON readers or dual writes; deployments use a clean
  prefix for this ABI change.
- Do not store score or decoded score coordinates in Worker metadata or
  Properties HASH values.
- Do not put property-index values inside Worker metadata or Properties.
- Do not auto-copy Properties to index keys.
- Do not scan Worker descriptors for DIRECT matching.
- Do not use `worker-id-owners` for global enumeration.
- Do not interpret index absence as transport unavailability.
- Do not let Adapter, Worker, or Server controller code write Worker score
  directly.
- Do not add broad locks, global repair scans, or transactions without a named
  invariant and bounded proof.
