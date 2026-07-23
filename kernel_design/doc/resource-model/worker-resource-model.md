# Worker Resource Model

Status: active new-kernel resource contract; Python executable spec
implemented; policy coverage partial.

This note fixes the first-cut metadata model for workers and worker groups in
the clean kernel design workspace. It intentionally keeps the model small:
metadata is a query projection and upsert declaration shape, not score truth.

## Core Decision

```text
WorkerGroup = one schedulable worker universe
Worker = one resource identity inside exactly one worker group
```

The resource model is intentionally a short tree:

```text
Task admission      -> exactly one selected WorkerGroup
WorkerCandidateConstraint -> worker predicates inside selected WorkerGroup
TaskItem / DeliverSeed -> worker-local EventHandler
Transport           -> internal delivery resource
```

`Task admission -> selected WorkerGroup` is fixed at task create/admission time.
In v0, admission accepts exactly one registered `workerGroupId`.
The selected `workerGroupId` is immutable while the task is running. If work
must use a different worker group, create a different task or stop/cancel and
recreate the task; do not hot-swap worker groups during dispatch.

`WorkerCandidateConstraint -> worker predicates` is matching inside the
selected worker group. Its `allocation_rule` map narrows workers by `workerId`,
placement, platform/worker attributes, or explicitly supported projected dynamic
query attributes.

`TaskItem / DeliverSeed -> EventHandler` is handler invocation inside the selected
worker. Kernel assignment passes the TaskItem's `eventCode` through without
using it for admission or matching. The selected Worker resolves it to a local
handler and reports an unsupported code as Worker failure evidence. EventCode
does not choose worker groups and does not prove Worker scheduling
serviceability.

`Transport` is an internal delivery resource. It resolves how to deliver already
assigned work to the selected WorkerId; it does not select workers. Assignment
snapshots the Worker's immutable `endpointManagerId` and uses it only to select
the sparse delivery bucket. Session, connection, and live reachability
observations remain outside the scheduling model.

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

In v0, a `WorkerId` is one scheduler-visible execution slot. One WorkerId may
have at most one active Worker lease continuation. The Worker score lease / hold
protects that slot from allocation through result or bounded lease expiry.
TaskItem dispatch validates or renews the exact fence; it does not release the
slot after DeliverSeed creation.

If one physical runtime can execute `N` independent TaskItems concurrently, it
must expose `N` logical WorkerIds. Those Workers may share one WorkerGroup and
one transport process, but each owns an independent score, lease, and
Adapter-mailbox field:

```text
physical runtime with concurrency 3
  -> worker-slot-1
  -> worker-slot-2
  -> worker-slot-3
```

One TaskItem remains one scheduling and result unit. If a Worker supports a
business batch operation, the caller places the bounded input collection inside
one TaskItem payload. The kernel still claims one Item, emits one DeliverSeed,
and receives one SeedResult. It does not merge independently appended
TaskItems, fan out partial outcomes, or reuse the Worker slot across Tasks.

This keeps score ownership, dirty handling, and release semantics single-group
and admission-fence oriented from the scheduler's point of view.

The complete allocation, dispatch exact recheck, result disposition, and timeout
sequence is defined once in
[Worker HOT_ACQUIRE Lease Protocol](../scheduling/worker-hot-acquire-lease-protocol.md).
This resource model does not own a second lease or capacity lifecycle.

## WorkerGroupDescriptor

```text
WorkerGroupDescriptor
  workerGroupId: string
  attributes: map<string, value>
  eventCodes: set<string>
  itemAllocationFields: set<string>
```

`workerGroupId` names a steady scheduling universe configured for operations.
Task admission fixes the worker group before task scheduling starts. The task
does not select or query worker groups during dispatch.

`attributes` are metadata/query fields. They may describe grouping, display,
classification, policy hints, or operator-facing facts. They are not live
worker score lease truth.

`eventCodes` declares the task item event families this worker group can
handle. It is capability metadata for external bootstrap, control-plane, and
operator validation. Kernel Item append, Worker matching, and dispatch do not
read it as an admission gate. It is not proof that a specific worker is
currently reachable, score-leased, or able to receive work.

`itemAllocationFields` declares which bounded candidate-source fields a
`ITEM_DRIVEN` Task may use in Item rules. `workerId` is built in;
`dynamic.<name>` also requires a registered handler-owned candidate index.
The declaration does not expose handler functions or Redis keys.

Normal WorkerGroup upsert may replace only `attributes`. `eventCodes` and
`itemAllocationFields` are immutable declarations; changing either requires a
separate owner command rather than reconnect-style upsert.

WorkerGroupDescriptor does not own:

```text
worker hot/recovery score
worker score lease / hold
per-Worker parallel capacity truth
transport session truth
adapter mailbox truth
task assignment truth
dynamic attribute current values
```

## Worker Declaration And Descriptor

```text
WorkerDeclaration
  workerId: string
  workerGroupId: string
  endpointManagerId: string
  attributes: map<string, value>
  dynamicAttributeNames: set<string>

WorkerDescriptor
  WorkerDeclaration fields
  platformAttributes: map<string, value>
```

`workerId` is the globally unique logical execution-slot identity used by Worker
score, assignment, and the field inside an Adapter delivery bucket.
Caller-provided IDs are
guarded globally in this first slice; kernel-generated identity is deferred.

`workerGroupId` is the only group relationship in v0.

`endpointManagerId` remains immutable declaration metadata in this first slice.
It is copied into CandidateWorker assignment evidence and selects the
DeliverSeed HASH at publication. It does not prove live reachability or
participate in matching, score ordering, or candidate selection. Removing or
changing it requires a separate controlled Worker-route command.

`WorkerDeclaration` is caller-owned connect/reconnect input. It cannot carry
platform attributes, score fields, polarity, laneRank, dirty, or time
coordinates. `WorkerDescriptor` is the complete runtime query projection.

`WorkerRuntime.upsert_worker` establishes or refreshes the Worker. First
appearance writes the immutable declaration identity and initializes the first
HOT_ACQUIRE score using runtime-owned lane configuration. Reconnect completely
replaces `attributes` and preserves `platformAttributes`. Every existing score
is reconciled to positive polarity with dirty=1 while preserving timeSlot and
laneRank. This invalidates pre-reconnect candidate evidence without releasing a
future hold. First score initialization remains positive with dirty=0 because
no older lease evidence exists.

`WorkerResourceCatalog.upsert_worker_group` creates a group or completely
replaces its `attributes`. Existing `eventCodes` are immutable; a mismatch is a
conflict. Worker `endpointManagerId` and `dynamicAttributeNames` are also
immutable after first appearance. Future mutation requires a separate owner
command rather than expanding upsert.

`workerGroupId` and `workerId` are globally unique in their own namespaces.
Worker upsert claims `workerId -> workerGroupId` once; reuse in another group is
a conflict. This uniqueness index is only a write guard, not a global query or
Worker-home discovery API.

Worker catalog reads and updates require `workerGroupId + workerId`. A bounded
batch contains one `workerGroupId` and many `workerIds`; callers must not submit
an unscoped worker-id batch and ask the catalog to rediscover or regroup worker
membership. `workerGroupId` is the logical resource locator. Group hashes,
worker-id hash buckets, or other physical partitions remain implementation
choices behind the catalog.

First-layer descriptor fields intentionally stop at resource identity, group
identity, endpoint-owner identity, and attribute buckets. Specific runtime,
package, handler, or compatibility versions belong in `attributes`.

`platformAttributes` is platform-written metadata. Worker upsert cannot supply
or replace it. The dedicated platform update merges attributes and should be
low-frequency. Examples:

```text
registeredAt
platformRegion
workerClass
trustLevel
ownerScope
```

`attributes` are Worker-reported metadata at connect/reconnect time, after
platform validation. Each upsert is a complete replacement snapshot. Examples:

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

Version and handler-bundle compatibility fields belong in `attributes`
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

## EventCode Declaration

`WorkerGroupDescriptor.eventCodes` declares the expected event-handler universe
for a WorkerGroup:

```text
every accepted worker in the group must be compatible with the group's eventCodes
```

An external Worker bootstrap or control-plane validator may verify:

```text
workerGroupId exists
attributes satisfy group policy / attributes
Worker declaration evidence or platform handler catalog covers eventCodes
```

That validation is not a kernel reconnect or scheduling transition. The kernel
does not reject TaskItems by comparing EventCode with this declaration. After
assignment, unsupported EventCode resolution is Worker execution failure, not
Adapter rejection and not Worker matching failure. Do not solve ordinary
handler differences by adding per-worker event binding rows in v0.

Version compatibility is metadata validation through `attributes`.
Assignment-dispatch must not interpret version fields directly as runtime
serviceability. Current scheduling admission still belongs to Worker score
polarity, lease, and hold.

## WorkerCandidateConstraint

`WorkerCandidateConstraint` is the matcher input owned by
assignment-dispatch. It combines candidate consumption priority, explicit
per-call worker limit, and one DSL rule map. It is
not a task policy owner, worker-group selector, score lease contract, or handler
routing contract.

One matcher call receives a candidate map. `allocation_rule` is already a
structured map; callers do not pass a JSON string and the worker matcher does
not own JSON parsing:

```python
candidate_constraints = {
  "task-1": WorkerCandidateConstraint(
    priority=0,
    limit=2,
    allocation_rule={
      "workerId": {"$in": ["worker-1", "worker-2"]},
      "platform.tier": {"$eq": "premium"},
      "attributes.runtime": {"$in": ["python", "java"]},
      "dynamic.battery": {"$gte": 20},
    },
  ),
}
```

The current worker matcher context exposes these fields:

```text
workerId
platform.*
attributes.*
dynamic.*
```

`endpointManagerId` is deliberately absent from the matcher context. It is
delivery-route metadata, not a WorkerConstraintQuery field.

These are worker-runtime owner fields, not DSL-reserved fields. `workerId` is a
normal context value that may participate in the same DSL operators as any
other value. It is not a descriptor attribute.

That evaluator capability is broader than Item-directed candidate discovery.
A TaskItem using `workerId` as its TARGETED source is restricted to bounded
`$eq/$in`; the matcher still evaluates the complete rule after exact lease.

The matcher derives dynamic read dependencies from the compiled `dynamic.*`
rule keys. `workerId`, `platform.*`, and `attributes.*` are already supplied by the
descriptor batch and require no handler read. The derived dependency union is
internal matcher state, not a caller-provided constraint field.

`priority` controls worker consumption across candidate constraints. Smaller
values run first; equal values are ordered by `candidateId` ascending. Map
insertion order is not scheduling policy.

`limit` is the maximum number of workers that candidate may consume in this
matcher call. It must be positive. It is not a worker discovery limit and does
not persist across calls. When one candidate reaches its limit, later workers
continue against the remaining candidates in resolved priority order.

`allocation_rule` is the only rule map. The independent DSL compiles and evaluates
domain-qualified fields against a two-level context map:

```python
ConstraintEvaluator.evaluate_match_rules(
  {
    "workerId": worker_id,
    "platform": descriptor.platform_attributes,
    "attributes": descriptor.attributes,
    "dynamic": resolved_dynamic_values,
  },
  allocation_rule,
)
```

It splits only the first `.`. For example, `dynamic.battery.level` resolves
domain `dynamic` and exact field name `battery.level`; it does not recursively
walk `battery -> level`. An unqualified field such as `workerId` is read from
the top-level context. `workerId`, `platform`, `attributes`, and `dynamic` are not
DSL-reserved names. The current worker matcher may choose those context domains,
but that is a worker-runtime owner decision. The evaluator does not know
descriptors, handlers, Redis, worker fields, or scheduling state.

At the start of one bounded matcher call, the matcher compiles every
`allocation_rule` map once, orders candidates by priority, derives the ordered
dynamic-field union, and batch-reads each field once. The compiled rules are
evaluated against one temporary context for the current worker.

The matcher then consumes worker ids in caller-supplied order:

```text
descriptor batch
  -> dynamic.* rule-key union
  -> one bounded handler call per required field
  -> for each workerId
       build one temporary context
       evaluate candidate constraints in priority order
       skip candidates already at limit
       first remaining match consumes the worker and stops candidate evaluation
```

Before dynamic IO, the matcher uses its already loaded descriptor batch to
derive the bounded workers that declare support for each dynamic field. It then
asks `WorkerDynamicAttributeRuntime` for that field. The runtime hides the
registered query handler and validates handler availability even when the
supported worker batch is empty. A missing handler is therefore a deterministic
worker-runtime configuration error. A missing, unsupported, or unresolved
handler result is written into the temporary worker context as unresolved and
fails closed for candidate rules that read it.

```text
get_worker_dynamic_attribute_values(workerGroupId, attributeName, boundedWorkerIds)
  -> workerId -> DynamicAttributeReadResult
```

The handler may implement this with `HMGET`, `ZMSCORE`, bitmap operations, or
another owner-specific bounded batch read. It must not discover workers outside
the supplied ids. Each declared field is called at most once per matcher call,
and its worker batch contains only ordered, unique workers that declared support
for that field.

`workerGroupId` is not a query field. It remains an outer parameter because it
chooses the worker universe, score bucket, and runtime namespace.

`eventCode` is not a query field or a kernel admission field. It passes through
assignment and later routes work to a worker-local handler; it does not match
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
dynamicAttributeNames cannot prove Worker scheduling serviceability
```

Dynamic attribute values are owned by built-in attribute functions hidden behind
worker-runtime surfaces:

```text
WorkerDynamicAttributeRuntime instance
  _updateHandlers: attrName -> update function
  _queryHandlers: attrName -> bounded batch query function
```

These private handler tables belong to the concrete runtime implementation.
Their callable and mapping types are not kernel contracts.

`WorkerCandidateMatcher` must not receive or store the query function table
directly. It validates descriptor support through `dynamic_attribute_names`,
then asks `WorkerDynamicAttributeRuntime` for one bounded dynamic attribute
read. The runtime invokes the owner-local handler internally.

The function owns:

```text
payload validation
normalization
query storage/index shape
fast point-write behavior
bounded batch-read behavior
```

## Dynamic Attribute Internal Flow

```text
worker-runtime receives or evaluates an attribute update
  -> read WorkerDescriptor
  -> require attrName in WorkerDescriptor.dynamicAttributeNames
  -> route by attrName or owner-defined attribute prefix
  -> resolve concrete runtime _updateHandlers[attrName]
  -> function validate / normalize payload
  -> function writes its own query storage/index
```

Dynamic attribute update is an internal worker-runtime function route, not an
external public API and not a global event bus. It must be bounded, point-write
oriented, fast-fail, and idempotent where possible. Routine high-frequency
updates such as heartbeat, load, or network observations must not emit global
scheduling events by default.

The first Python kernel surface exposes bounded update and read operations
through `WorkerDynamicAttributeRuntime`. Update requires
`workerGroupId + workerId`, validates the worker descriptor and the
`dynamicAttributeNames` allowlist, then dispatches accepted attributes to
owner-local handlers. Read accepts one declared attribute and one bounded,
descriptor-supported worker batch from the matcher. Neither operation writes
worker score leases directly.

`WorkerDynamicAttributeRuntime` is separate from `WorkerResourceCatalog`.
`WorkerRuntime` owns Worker upsert and score initialization/reconnect polarity.
Catalog owns worker-group
declarations, bounded descriptor reads, and low-frequency descriptor metadata.
Dynamic attribute runtime owns bounded update/read routing and handler
dispatch for projected, policy-readable facts. It must not become a second
worker descriptor store, worker lifecycle owner, score owner, or policy engine.

Dynamic attributes are query/projection facts. A `heartbeat` dynamic attribute
does not require a second heartbeat owner. If adapter/session runtime already
has heartbeat or reachability information, the dynamic attribute function may
batch-read or project that existing fact for query use. It must not become the
Worker scheduling-serviceability truth.

If a dynamic attribute is relevant to scheduling policy, the worker-runtime
matching path may batch-read the attribute owner's current values for a bounded
leased Worker batch. Only bounded candidate matching should receive candidate
constraints; worker score lease / hold writes should not receive raw rule maps.
Assignment-dispatch and worker score primitives must not interpret dynamic
attribute payloads directly, and dynamic attribute updates must not drive
Worker serviceability classification by themselves.

Dynamic attribute changes do not automatically mark worker score dirty. A
handler should consider dirty only when the changed attribute participates in
the candidate constraint / matcher validation dependency set of an existing
assignment plan or hot score lease continuation, and the new value invalidates
or may invalidate the recorded match evidence. If there is no such continuation,
the next matcher read observes the new value directly.

Load or local queue-depth dynamic attributes may narrow or rank existing Worker
slots. They cannot make one WorkerId represent multiple independently leased
execution slots. Physical executor concurrency is projected by provisioning or
draining logical WorkerIds, not by minting parallel assignments from an
attribute value.

## Scheduling Boundary

The stable model has three resource/handler relationships:

```text
Task admission      -> selected WorkerGroup
WorkerCandidateConstraint -> worker predicates inside selected WorkerGroup
TaskItem / DeliverSeed -> EventHandler
```

`Task admission -> selected WorkerGroup` validates one registered group before
task scheduling. It is exactly one group in v0 and immutable while the task is
running. Assignment-dispatch does not query or choose groups on each round.

`WorkerCandidateConstraint -> worker predicates` is worker matching inside the
selected worker group. Its `allocation_rule` may constrain `workerId`, placement,
Worker attributes, platform attributes, or explicitly supported projected
dynamic attributes. These constraints narrow the Worker ids returned by the
lease-first allocation step; matcher does not attempt or renew Worker-score
leases.

`workerId` inside `allocation_rule` is only a hard filter inside the task's selected
`workerGroupId`. The Worker must come from bounded due HOT score observation and
win an exact-score lease CAS before matcher validation. It is not a worker
group selector and not a transport target.

Dynamic attribute matching is deliberately narrow in v0. Assignment-dispatch
does not perform arbitrary dynamic-attribute multi-index queries. Each
`WorkerCandidateConstraint.allocation_rule` map declares the predicates; the
matcher derives its `dynamic.*` dependencies and batch-reads each field for
bounded workers whose descriptors declare support. The acquired
values are read only while evaluating the current worker context. Item-directed
candidate discovery such as `battery > 20` is allowed only when the dynamic
attribute runtime installs an explicit bounded candidate-query handler.

The first worker-runtime matching surface may expose a bounded batch matcher:

```text
match_worker_candidates(
  workerGroupId,
  workerLeaseScores={workerId: opaqueLeaseScore, ...},
  {candidateId: WorkerCandidateConstraint, ...},
)
  -> WorkerCandidateAcquisition={
       candidateId: CandidateWorkerEntry[],
       ...,
     }
```

The candidate-constraint map makes candidate identity unique. Each constraint
carries explicit `priority`, `limit`, and map-shaped `allocation_rule`. The matcher
sorts by priority ascending (`0` highest) and `candidateId` ascending, then each worker is
considered by the first matching candidate with remaining match limit. Every
matched Worker appears in exactly one candidate result. Each returned
`CandidateWorkerEntry` contains the Worker id, WorkerGroup id, immutable
`endpointManagerId` route snapshot, and opaque lease score. The route snapshot
selects only the DeliverSeed mailbox bucket and is not part of matching. The
matcher copies but never parses or modifies the lease score.
Unmatched Worker ids are not returned; their already-acquired leases remain
held until natural expiry.
A matcher call handles exactly one selected `workerGroupId`; assignment-dispatch
must partition candidates by Worker group and acquire bounded Worker lease
evidence before calling it. The selected candidate acquirer unions and
deduplicates source Worker ids, batch-leases or validates them by exact CAS, and
passes only lease successes as one opaque `workerId -> score` map. The matcher
owns descriptor reads, dynamic reads, and matching for only those supplied
Worker ids. It evaluates complete rules in priority order, assigns each Worker
at most once, and leaves unmatched leases to expire naturally. The matcher
directly materializes the final acquisition result.
The matcher returns every input candidate in resolved priority order. An empty
entry means no match. It must not acquire or discover additional Workers and
must not become
`find_all_matching_workers(query)` or a global worker query service.

There is no separate assignment-continuation surface in v0. Worker occupation
uses `WorkerScoreCore` score lease / hold primitives directly. If a later
executable spec proves a persisted cross-round assignment continuation is
required, add that owner then. Do not keep a placeholder surface now.

`TaskItem / DeliverSeed -> EventHandler` is worker-local execution routing. The
item `eventCode` resolves the handler only after the task has a selected worker
group and assignment-dispatch has selected a concrete worker.

Live adapter / transport facts are not part of the public worker resource model.
`endpointManagerId` remains required immutable declaration metadata in the
current executable-spec shape. Assignment snapshots it to select an Adapter
mailbox bucket, but it is not session identity or reachability evidence. Adapter
implementation identity, session, route, connection, and polling-channel facts
remain transport-local. The Adapter mailbox is kernel handoff state, not live
transport state. If a policy needs a network category, expose it as a Worker
attribute such as `networkType`; do not expose transport identifiers as
worker-selection facts.

Descriptor metadata is the low-frequency matching surface used inside a
pre-bound worker group. Candidate sources propose a small bounded Worker
universe; descriptor and dynamic point reads validate it after exact score
lease. Metadata does not decide which WorkerGroup a Task may use:

```text
task admission
  -> validates and fixes workerGroupId
task eventCode
  -> passes through to selected Worker's local handler dispatch
pre-bound workerGroupId
  -> candidate source proposes bounded Worker ids
  -> WorkerScoreCore exact lease validates serviceability and slot hold
  -> WorkerCandidateConstraint.allocation_rule validates current attributes
```

Scheduling must not stop at descriptor matches. It reads worker-runtime owner
surfaces before producing a selected worker:

```text
task descriptor workerGroupId -> worker score home bucket
worker score -> hot/recovery acquisition coordinate
WorkerScoreCore lease / hold -> current serviceability and slot-occupation decision
assignment-dispatch -> selected worker + Item score claim + DeliverSeed
```

Descriptor metadata can be used by worker-runtime validation, policy mapping,
query views, and diagnostics. It is a candidate discovery / matching input; it
must not become a second score lease owner.

In v0, Worker occupation is a bounded score lease / hold of exactly one
scheduler-visible execution slot. Allocation creates the exact fence, TaskItem
dispatch validates or renews it before claiming an Item, and result routing
either exact-releases it after Worker execution evidence or exact-demotes it to
RECOVERY_RECHECK after Adapter rejection evidence. With no valid result, natural
lease expiry restores visibility. Do not model a capacity pool inside one
`WorkerDescriptor`, and do not release the fence early to simulate concurrency.

The worker score model remains separate:

```text
WorkerDescriptor
  resource identity, endpoint owner locator, metadata, and dynamic attribute allowlist

WorkerDynamicAttributeRuntime
  bounded dynamic attribute update/read route and owner-local handler dispatch

WorkerScore
  HOT_ACQUIRE / RECOVERY_RECHECK acquisition coordinate

WorkerScoreLease
  validates metadata, optional dynamic query attributes, serviceability
  evidence and score lease / hold
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
eventCode inside WorkerCandidateConstraint.allocation_rule
workerGroupId inside WorkerCandidateConstraint.allocation_rule
adapter session / mailbox / connection state inside WorkerDescriptor
runtime score / dirty / score lease fields inside WorkerDescriptor
parallel execution capacity inside one WorkerId
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
the same physical executor needs independently managed logical slots per group
the same worker needs different score lease lane per group
```

Add public metadata revision/signature only when an executable spec proves a
stale-fence invariant that cannot be handled by score fence / score lease.

Add event binding rows only when `WorkerGroupDescriptor.eventCodes` is too weak
to express group-level event ownership. Do not add them to handle ordinary
worker version differences; use `attributes` plus platform validation for
that. Do not add event binding rows preemptively.

## Minimal Python Shape

```python
@dataclass(frozen=True)
class WorkerGroupDescriptor:
    worker_group_id: str
    attributes: Mapping[str, JsonValue]
    event_codes: frozenset[str]
    item_allocation_fields: frozenset[str]


@dataclass(frozen=True)
class WorkerDeclaration:
    worker_id: str
    worker_group_id: str
    endpoint_manager_id: str
    attributes: Mapping[str, JsonValue]
    dynamic_attribute_names: frozenset[str]


@dataclass(frozen=True)
class WorkerDescriptor:
    worker_id: str
    worker_group_id: str
    endpoint_manager_id: str
    attributes: Mapping[str, JsonValue]
    platform_attributes: Mapping[str, JsonValue]
    dynamic_attribute_names: frozenset[str]
```

The Python executable spec may start with this shape directly. Runtime score,
score lease, dynamic attribute indexes, and transport session facts should remain
separate owner structures.
