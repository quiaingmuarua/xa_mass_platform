# Worker Runtime Redis Shape

Status: active Java Kernel Worker Runtime Redis ABI.

## Namespace And Owners

```text
xa_mass:<scope>:worker:...
```

`xa_mass` is the fixed root namespace. `scope` is the validated data-isolation
boundary (`profile_*` for a runtime profile, `test_*` for one proof run).
The Redis DB number is only a connection coordinate. `workerGroupId` is the
Worker home bucket and score partition. Redis structures are owned separately:

```text
WorkerResourceCatalog      WorkerGroup descriptors plus Worker metadata/properties
WorkerRuntime              Worker metadata establishment and snapshot refresh
WorkerScoreCore            Worker scheduling score ZSET
```

Delivery mailboxes, Worker results, and optional serviceability evidence use
the `delivery`, `result`, and Worker-local `serviceability` domains under the
same root and scope. Task assignment, connection state, and execution truth
never enter the Worker resource keys. Keys do not use Redis Cluster hash tags;
Cluster support requires a separate design.

## WorkerGroup Descriptors

```text
xa_mass:<scope>:worker:groups
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

Registration behavior:

```text
HSETNX establishes WorkerGroup
identical descriptor -> NOOP
changed attributes and/or eventCodes -> CONFLICT, stored value unchanged
stored descriptor identity mismatch -> INVALID
damaged stored descriptor -> INVALID
```

`workerGroupId` is the stable HASH field and scheduling partition identity.
`attributes` and `eventCodes` form one create-only control-plane declaration.
They are not read by Matcher or Dispatch and do not assert the Handler set
currently installed on every Worker.

## Worker Metadata And Properties

```text
xa_mass:<scope>:worker:metadata:<workerGroupId>
  HASH field = workerId
  value       = canonical WorkerMetadata JSON

xa_mass:<scope>:worker:properties:<workerGroupId>
  HASH field = workerId
  value       = canonical workerProperties JSON object

xa_mass:<scope>:worker:id_owners
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

The `worker:id_owners` HASH is the immutable identity fence and backs only the bounded
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
`workerProperties` or score.

The Server-owned identity and endpoint Binding registries use a separate
namespace:

```text
xa_mass:<scope>:worker:identity:<workerGroupId>
  HASH field = clientWorkerKey
  value       = canonical UUID workerId

xa_mass:<scope>:worker:binding:<00..ff>
  HASH field = canonical UUID workerId
  value       = endpointManagerId
```

Identity registration establishes long-lived external identity. Binding
establishes the persistent delivery route used to project `endpointManagerId`
into Kernel Worker metadata. The public Worker Prepare use case composes these
Server owners with Worker upsert, but does not merge their Redis ownership.
The 256 bucket suffix is the first SHA-256 byte of the canonical workerId and
only limits HASH size. Neither registry is a Kernel owner key, candidate
catalog, authentication record, or connectivity truth.

## Worker Score

```text
xa_mass:<scope>:worker:score:<workerGroupId>
  ZSET member = workerId
  score       = opaque Worker score encoding
```

Only `WorkerScoreCore` interprets or mutates score values. Positive values are
HOT_ACQUIRE scheduling-serviceability; negative values are RECOVERY_RECHECK.
Time, lane rank, dirty fence, exact compare-and-set, lease, hold, recovery, and
release semantics are defined in
[Worker Score Band Scheduling](../score/worker-score-band-scheduling.md).

Java implements the complete production caller closure for candidate reads,
exact lease acquisition/renewal, Serviceability polarity/recovery operations,
and Result release. Worker upsert still calls only get/initialize. Real Redis
proof guards the shared bytes and transitions.

Resource writes do not require a Worker lease and do not mutate an existing
score. A future explicit lifecycle operation must own any
RECOVERY_RECHECK-to-HOT_ACQUIRE transition.

## Bounded Reads

Catalog reads are caller-bounded:

```text
get_worker_group_descriptors(explicit WorkerGroupIds)
sample_worker_group_descriptors(sampleLimit <= 100)
get_worker_descriptors(one WorkerGroupId, explicit WorkerIds)
sample_worker_descriptors(one WorkerGroupId, sampleLimit <= 100)
```

WorkerGroup preview performs exactly one positive-count
`HRANDFIELD ... WITHVALUES` against the Group HASH. It is an unordered,
unstable, incomplete sample with no cursor, total, or completeness claim.
Unreadable JSON and field/descriptor identity mismatch are returned as
`workerGroupId -> None`.

Worker preview samples metadata with one positive-count
`HRANDFIELD ... WITHVALUES`, then loads the corresponding properties rows. It
is an unordered incomplete sample with no cursor, total, stability, or
completeness claim. A missing or unreadable row on either side is returned as
`workerId -> None`.

Runtime View exposes random WorkerGroup and Worker descriptor samples plus the
existing explicit-ID reads. It does not join score, mailbox, connection, or
Task assignment data.

## Consistency And Guardrails

Strong stale-state protection is reserved for score compare-and-set and
identity ownership. WorkerGroup registration is atomic create-only;
Worker-property writes use bounded eventual convergence.

- Do not add legacy key readers or dual writes; deployments use a new scope for
  this ABI change and old keys remain untouched.
- Keep each Worker Properties value as the complete canonical JSON Map; do not
  wrap it in an update-time envelope or add timestamp arbitration.
- Do not store score or decoded score coordinates in Worker metadata or
  Properties HASH values.
- Do not scan Worker descriptors for item-rule on-demand matching.
- Reject `index.*` allocation requirements rather than adding a hidden
  projection store.
- Do not use the `worker:id_owners` HASH for global enumeration.
- Do not let Adapter, Worker, or Server controller code write Worker score
  directly.
- Do not add broad locks, global repair scans, or transactions without a named
  invariant and bounded proof.
