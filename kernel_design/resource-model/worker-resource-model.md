# Worker Resource Model

Status: new-kernel design note, not current implementation truth and not an
implementation roadmap.

This note fixes the first-cut metadata model for workers and worker groups in
the clean kernel design workspace. It intentionally keeps the model small:
metadata is query projection and registration shape, not runtime truth.

## Core Decision

```text
WorkerGroup = one schedulable worker universe
Worker = one resource identity inside exactly one worker group
```

The resource model is intentionally a short tree:

```text
Project / workload -> allowed WorkerGroups
Task                -> exactly one selected WorkerGroup
WorkerConstraintQuery -> worker identity / attribute predicates inside selected WorkerGroup
Work / item / seed  -> worker-local EventHandler
Transport           -> internal delivery resource
```

`Project / workload -> allowed WorkerGroups` is operations configuration. A
project may allow multiple worker groups.

`Task -> selected WorkerGroup` is fixed at task create/admission time. In v0, a
task selects exactly one `workerGroupId` from the project/workload allowed set.
The selected `workerGroupId` is immutable while the task is running. If work
must use a different worker group, create a different task or stop/cancel and
recreate the task; do not hot-swap worker groups during dispatch.

`WorkerConstraintQuery -> worker identity / attribute predicates` is matching
inside the selected worker group. It narrows workers by reserved `workerId`,
placement, static/system attributes, or explicitly supported projected dynamic
query attributes.

`Work / item / seed -> EventHandler` is handler invocation inside the selected
worker. The work item's `eventCode` validates against the selected
`WorkerGroupDescriptor.eventCodes` and resolves to a worker-local handler. It
does not choose worker groups and does not prove worker availability.

`Transport` is an internal delivery resource. It resolves how to deliver already
assigned work to the selected worker; it does not select workers or expose
adapter/session/mailbox facts as scheduling model fields.

In v0, a worker has exactly one `workerGroupId`. Do not add membership rows,
multi-group joins, dynamic group selectors, or group-local event binding rows
until a concrete executable-spec need proves the extra model is required.

If one physical process must serve multiple worker groups, model it as multiple
logical workers instead of one worker with many groups:

```text
physical runtime process
  -> logical worker A in workerGroupA
  -> logical worker B in workerGroupB
```

In v0, a `Worker` is the scheduler-visible execution identity. It may still
process multiple work items concurrently. Worker score lease / hold protects
only the short assignment decision window; it is not an execution-duration lock
and should be released after assignment / deliver seed creation succeeds.

Concurrency and capacity are policy inputs, not worker score truth. Represent
them through static attributes, projected dynamic attributes such as
`runningCount` / `freeSlots`, worker-side backpressure, and assignment policy.
Do not keep a worker score lease open merely to express in-flight execution.

This keeps score ownership, dirty handling, and release semantics single-group
and admission-fence oriented from the scheduler's point of view.

## WorkerGroupDescriptor

```text
WorkerGroupDescriptor
  workerGroupId: string
  attributes: map<string, value>
  eventCodes: set<string>
```

`workerGroupId` names a steady scheduling universe configured for operations.
Project / workload binding chooses the worker group before task scheduling
starts. A task inherits its project-bound `workerGroupId`; the task itself does
not select or query worker groups during dispatch.

`attributes` are metadata/query fields. They may describe grouping, display,
classification, policy hints, or operator-facing facts. They are not live
worker score lease truth.

`eventCodes` declares the task item event families this worker group can
handle. It validates that the project-bound worker group is allowed to serve the
task item's event, but it is not a per-dispatch group discovery mechanism and
not proof that a specific worker is currently reachable, score-leased, or able to
receive work.

WorkerGroupDescriptor does not own:

```text
worker hot/recovery score
worker score lease / hold
worker capacity truth
transport session truth
adapter mailbox truth
task assignment truth
dynamic attribute current values
```

## WorkerDescriptor

```text
WorkerDescriptor
  workerId: string
  workerGroupId: string
  systemMetadata: map<string, value>
  staticAttributes: map<string, value>
  dynamicAttributeNames: set<string>
```

`workerId` is the resource identity used by worker score and assignment.

`workerGroupId` is the only group relationship in v0.

Worker catalog reads and updates require `workerGroupId + workerId`. A bounded
batch contains one `workerGroupId` and many `workerIds`; callers must not submit
an unscoped worker-id batch and ask the catalog to rediscover or regroup worker
membership. `workerGroupId` is the logical resource locator. Group hashes,
worker-id hash buckets, or other physical partitions remain implementation
choices behind the catalog.

First-layer descriptor fields intentionally stop at resource identity, group
identity, and attribute buckets. Specific runtime, package, handler, or
compatibility versions belong in `staticAttributes`.

`systemMetadata` is platform-written metadata. It is writable, but should
be low-frequency. Examples:

```text
registeredAt
platformRegion
workerClass
trustLevel
ownerScope
```

`staticAttributes` are worker-reported metadata at register/connect time, after
platform validation. They should be low-frequency and refreshed only by
register, reconnect, or explicit metadata update. Examples:

```text
cpuClass
gpuClass
runtime
runtimeVersion
os
arch
region
customTraits
```

Version and handler-bundle compatibility fields belong in `staticAttributes`
when they are needed. They are ordinary low-frequency metadata, not first-layer
descriptor fields, not metadata revisions, not stale fences, and not
score lease tokens.

`dynamicAttributeNames` is an allowlist of dynamic attribute names that this
worker is allowed to update. It is not the current value of those attributes.

Examples:

```text
dynamicAttributeNames = {
  "heartbeat",
  "battery",
  "network",
  "load"
}
```

## EventCode Promise

`WorkerGroupDescriptor.eventCodes` is a group promise. In v0, a worker group is
a homogeneous event-handler universe:

```text
every accepted worker in the group must be compatible with the group's eventCodes
```

Platform validation must happen when a worker registers, connects, or changes
group:

```text
workerGroupId exists
staticAttributes satisfy group policy / attributes
worker registration evidence or platform handler catalog covers eventCodes
```

If a worker cannot satisfy the group's event promise, reject the worker from
that worker group. Do not solve this by adding per-worker event binding rows in
v0.

Version compatibility is metadata validation through `staticAttributes`.
Assignment-dispatch must not interpret version fields directly as runtime
availability. Current usability still belongs to worker-runtime score lease /
hold.

## WorkerConstraintQuery

`WorkerConstraintQuery` is a small Mongo-like predicate tool for candidate
filtering and matching inside an already selected worker group. It is not a
task policy owner, not a worker-group selector, not a score lease contract, and
not a handler routing contract.

The v0 query surface is intentionally narrow, but it is still a real mechanism.
Unsupported operators are explicit omissions until an executable spec proves
the need; they should not be replaced with ad hoc maps, raw JSON interpretation,
or owner-mixed shortcuts.

The query is an implicit `AND` over validated flat fields:

```python
WorkerConstraintQuery({
  "workerId": {"$in": ["worker-1", "worker-2"]},
  "system.tier": {"$eq": "premium"},
  "static.runtime": {"$in": ["python", "java"]},
  "dynamic.battery": {"$gte": 20},
})
```

Supported fields:

```text
workerId
system.*
static.*
dynamic.*
```

`workerId` is a reserved identity predicate. It is a hard filter over candidate
workers and only supports `$eq` / `$in`. It is not a descriptor attribute.

The query validates and freezes its complete structure during construction. It
also precompiles `system.*`, `static.*`, and `dynamic.*` into category field
indexes. The matcher uses those indexes to assemble one flat value map per
bounded worker from only the fields required by applicable candidate queries:

```text
workerId
system.tier
static.runtime
dynamic.battery
```

System and static values come directly from their descriptor maps. Dynamic
values are resolved by attribute handler and inserted into the same flat map.
The generic evaluator compares that assembled map with each validated query; it
does not know worker descriptors, handlers, Redis, or scheduling state.

The matcher assembles this map in two stages. It first adds `workerId`, required
system fields, and required static fields, then removes queries that fail those
cheap predicates. It resolves and appends only the dynamic fields required by
the remaining queries. Each required dynamic attribute is point-read at most
once per worker inside one matcher call.

`workerGroupId` is not a query field. It remains an outer parameter because it
chooses the worker universe, score bucket, and runtime namespace.

`eventCode` is not a query field. It validates the selected group's event-code
promise and later routes work to a worker-local handler; it does not match
individual workers.

Supported v0 operators:

```text
$eq / $equal
$ne
$gt / $gte
$lt / $lte
$in
$exists
```

Do not add these in v0:

```text
$or
$and as explicit node
$not
$regex
$where / function expression
implicit type conversion
deep object traversal
```

## Dynamic Attribute Boundary

Dynamic attributes are separated from `WorkerDescriptor` because each dynamic
attribute may need a different storage/index shape and update function.

```text
heartbeat -> zset(workerId -> lastSeenTime)
battery   -> zset(workerId -> batteryLevel)
network   -> hash(workerId -> networkType)
load      -> hash / zset / bitmap, depending on policy
```

`WorkerDescriptor.dynamicAttributeNames` only decides whether an update is allowed:

```text
dynamicAttributeNames can accept or reject an update
dynamicAttributeNames cannot represent current attribute value
dynamicAttributeNames cannot replace worker score
dynamicAttributeNames cannot be used as direct scheduling truth
dynamicAttributeNames cannot prove worker availability
```

Dynamic attribute values are owned by built-in attribute functions:

```text
update_dynamic_attributes_dict
  attrName -> update function

query_dynamic_attributes_dict
  attrName -> query function
```

The function owns:

```text
payload validation
normalization
query storage/index shape
fast point-write behavior
```

## Dynamic Attribute Internal Flow

```text
worker-runtime receives or evaluates an attribute update
  -> read WorkerDescriptor
  -> require attrName in WorkerDescriptor.dynamicAttributeNames
  -> route by attrName or owner-defined attribute prefix
  -> resolve update_dynamic_attributes_dict[attrName]
  -> function validate / normalize payload
  -> function writes its own query storage/index
```

Dynamic attribute update is an internal worker-runtime function route, not an
external public API and not a global event bus. It must be bounded, point-write
oriented, fast-fail, and idempotent where possible. Routine high-frequency
updates such as heartbeat, load, or network observations must not emit global
scheduling events by default.

The first Python kernel surface exposes this as a narrow
`WorkerDynamicAttributeRuntime.update_worker_dynamic_attributes(...)` ingress.
That method requires `workerGroupId + workerId`, validates the worker descriptor
and the `dynamicAttributeNames` allowlist, then dispatches accepted attributes
to owner-local handlers. It does not expose dynamic attribute query values and
does not write worker score leases
directly.

`WorkerDynamicAttributeRuntime` is separate from `WorkerResourceCatalog`.
Catalog owns worker resource declarations and low-frequency descriptor
metadata. Dynamic attribute runtime owns only update routing and handler
dispatch for projected, policy-readable facts. It must not become a second
worker descriptor store, worker lifecycle owner, score owner, or policy engine.

Dynamic attributes are query/projection facts. A `heartbeat` dynamic attribute
does not require a second heartbeat owner. If adapter/session runtime already
has heartbeat or reachability information, the dynamic attribute function may
point-read or project that existing fact for query use. It must not become the
worker availability truth.

If a dynamic attribute is relevant to scheduling policy, the worker-runtime
matching path may point-read the attribute owner's current value before
score lease. Only bounded candidate matching should receive
`WorkerConstraintQuery`; worker score lease / hold writes should not
receive raw constraint queries.
Assignment-dispatch and worker score primitives must not interpret dynamic
attribute payloads directly, and dynamic attribute updates must not drive
worker availability by themselves.

Dynamic attribute changes do not automatically mark worker score dirty. A
handler should consider dirty only when the changed attribute participates in
the `WorkerConstraintQuery` / matcher validation dependency set of an existing
assignment plan or hot score lease continuation, and the new value invalidates
or may invalidate the recorded match evidence. If there is no such
continuation, the next matcher read observes the new value directly.

Concurrent execution control is one expected use of dynamic attributes. For
example, a worker may project `runningCount`, `freeSlots`, load, or local
queue-depth attributes. These values can narrow or rank candidate workers
through policy / matching, while worker score lease remains only the short
assignment fence.

## Scheduling Boundary

The stable model has three resource/handler relationships:

```text
Project / workload -> allowed WorkerGroups
Task                -> selected WorkerGroup
WorkerConstraintQuery -> worker identity / attribute predicates inside selected WorkerGroup
Work / item / seed  -> EventHandler
```

`Project / workload -> allowed WorkerGroups` is operations configuration. It is
stable and should be resolved before task dispatch. Assignment-dispatch should
not query worker groups on every scheduling round.

`Task -> selected WorkerGroup` is chosen at task create/admission time from the
allowed set. It is exactly one group in v0 and immutable while the task is
running.

`WorkerConstraintQuery -> worker identity / attribute predicates` is worker
matching inside the selected worker group. The query may constrain `workerId`,
placement, static attributes, system attributes, or explicitly supported
projected dynamic attributes. These constraints narrow worker candidates; they
do not replace worker-runtime score lease.

`workerId` inside `WorkerConstraintQuery` is only a hard filter inside the
task's selected `workerGroupId`. It still must pass worker score acquire and
worker-runtime score lease. It is not a worker group selector and not a transport
target.

Dynamic attribute matching is deliberately narrow in v0. Assignment-dispatch
must not perform arbitrary dynamic-attribute multi-index queries. The default
path is owner-approved candidate matching point-reading the dynamic attribute
owner's current value before score lease. If a later executable spec needs
candidate discovery such as `battery > 20`, the dynamic attribute query function
must expose a bounded candidate index explicitly.

The first worker-runtime matching surface may expose a bounded batch matcher:

```text
match_worker_candidates(
  workerGroupId,
  workerIds,
  [(candidateId, WorkerConstraintQuery), ...]
)
```

The input is an ordered tuple list, not a map: candidate order may carry
assignment priority. `candidateId` must be unique within one call. A matcher
call handles exactly one selected `workerGroupId`; assignment-dispatch must
partition candidates by worker group before calling it. The matcher returns one
ordered `(candidateId, matchedWorkerIds)` entry for every input candidate.
Empty `matchedWorkerIds` means no match. It matches only the supplied worker
ids and does not carry `observedWorkerScore`; assignment-dispatch keeps score
fences from worker score acquire as sidecar evidence for later score lease. It
must not become `find_all_matching_workers(query)` or a global worker query
service.

There is no separate assignment-continuation surface in v0. Worker occupation
uses `WorkerScoreCore` score lease / hold primitives directly. If a later
executable spec proves a persisted cross-round assignment continuation is
required, add that owner then. Do not keep a placeholder surface now.

`Work / item / seed -> EventHandler` is worker-local execution routing. The
item `eventCode` resolves the handler only after the task has a selected worker
group and assignment-dispatch has selected a concrete worker.

Adapter / transport facts are not part of the public worker resource model.
Workers may connect through adapters, but adapter identity, mailbox, session,
route, and delivery queue remain internal transport delivery details. If an
operator or policy needs a network category, expose it as a worker attribute
such as `networkType`; do not expose adapter/session identifiers as worker
selection facts.

Descriptor metadata is the low-frequency query and matching surface used by
worker allocation inside a pre-bound worker group. Its purpose is to help
assignment-dispatch find a small set of plausible workers before runtime
score lease. It does not decide which worker group a task may use:

```text
project/workload binding
  -> task inherits workerGroupId
task eventCode
  -> validates against WorkerGroupDescriptor.eventCodes
pre-bound workerGroupId
  -> WorkerConstraintQuery filters identity/static/system/query attributes
  -> WorkerScoreCore score lease validates current usability/capacity/resource hold
```

Scheduling must not stop at descriptor matches. It reads worker-runtime owner
surfaces before producing a selected worker:

```text
task inherited workerGroupId -> worker score home bucket
worker score -> hot/recovery acquisition coordinate
WorkerScoreCore lease / hold -> current usability/capacity/resource hold decision
assignment-dispatch -> selected worker + work claim + deliver seed
```

Descriptor metadata can be used by worker-runtime validation, policy mapping,
query views, and diagnostics. It is a candidate discovery / matching input; it
must not become a second score lease owner.

In v0, worker occupation is a short score lease / hold of one
scheduler-visible worker identity during assignment. A selected worker is
score-leased for the current assignment window or it is not. The lease should be
released after assignment / deliver seed creation, so it does not serialize all
work execution for that worker. Do not model capacity pools inside
`WorkerDescriptor`; express concurrent capacity through attributes and policy
until an executable spec proves a separate capacity owner is needed.

The worker score model remains separate:

```text
WorkerDescriptor
  metadata and dynamic attribute allowlist

WorkerDynamicAttributeRuntime
  dynamic attribute update route and owner-local handler dispatch

WorkerScore
  HOT_ACQUIRE / RECOVERY_RECHECK acquisition coordinate

WorkerScoreLease
  validates metadata, optional dynamic query attributes, reachability, capacity,
  and score lease / hold
```

## Deliberate Non-Goals

Do not add these to v0:

```text
WorkerGroupMembership
multi-workerGroup worker
dynamic group selector
group-local worker rank / quota / lane
metadataRevision as public descriptor field
metadataSignature as public descriptor field
dynamic attribute function refs carried by WorkerDescriptor
dynamic attribute current values inside WorkerDescriptor
eventCode inside WorkerConstraintQuery
workerGroupId inside WorkerConstraintQuery
adapter session / mailbox / connection state inside WorkerDescriptor
runtime score / dirty / score lease fields inside WorkerDescriptor
```

If the executable spec needs stale metadata fencing, worker-runtime may compute
an internal scheduling signature from selected metadata and evidence. That
signature should remain worker-runtime evidence, not a public descriptor field,
until a concrete invariant requires public exposure.

## Expansion Triggers

Add `WorkerGroupMembership` only when one of these becomes unavoidable:

```text
the same worker must have different weight per group
the same worker must be enabled in one group and disabled in another
the same worker needs different quota/capacity per group
the same worker needs different score lease lane per group
```

Add public metadata revision/signature only when an executable spec proves a
stale-fence invariant that cannot be handled by score fence / score lease.

Add event binding rows only when `WorkerGroupDescriptor.eventCodes` is too weak
to express group-level event ownership. Do not add them to handle ordinary
worker version differences; use `staticAttributes` plus platform validation for
that. Do not add event binding rows preemptively.

## Minimal Python Shape

```python
@dataclass(frozen=True)
class WorkerGroupDescriptor:
    worker_group_id: str
    attributes: Mapping[str, JsonValue]
    event_codes: frozenset[str]


@dataclass(frozen=True)
class WorkerDescriptor:
    worker_id: str
    worker_group_id: str
    system_metadata: Mapping[str, JsonValue]
    static_attributes: Mapping[str, JsonValue]
    dynamic_attribute_names: frozenset[str]
```

The Python executable spec may start with this shape directly. Runtime score,
score lease, dynamic attribute indexes, and transport session facts should remain
separate owner structures.
