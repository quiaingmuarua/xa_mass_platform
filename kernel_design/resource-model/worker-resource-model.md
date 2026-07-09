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
Task demand         -> WorkerDescriptor attributes inside selected WorkerGroup
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

`Task demand -> WorkerDescriptor attributes` is matching inside the selected
worker group. It narrows workers by targetWorkerId, placement, static/system
attributes, or projected dynamic query attributes.

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

In v0, a `Worker` is the scheduler-visible exclusive execution resource, such
as one exclusive thread / handler lane / execution slot. CPU time-slicing is
outside this model. If multiple logical workers share a physical device such as
a GPU, that is not represented as multi-group worker ownership in v0; expose it
later through dynamic query attributes or a dedicated runtime owner only after
an executable spec proves the need.

This keeps score ownership, admission, capacity, dirty handling, and release
semantics single-group and single-resource from the scheduler's point of view.

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
worker admission truth.

`eventCodes` declares the task item event families this worker group can
handle. It validates that the project-bound worker group is allowed to serve the
task item's event, but it is not a per-dispatch group discovery mechanism and
not proof that a specific worker is currently reachable, admitted, or able to
receive work.

WorkerGroupDescriptor does not own:

```text
worker hot/recovery score
worker runtime admission
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
  systemAttributes: map<string, value>
  staticAttributes: map<string, value>
  dynamicAttributes: set<string>
```

`workerId` is the resource identity used by worker score and assignment.

`workerGroupId` is the only group relationship in v0.

First-layer descriptor fields intentionally stop at resource identity, group
identity, and attribute buckets. Specific runtime, package, handler, or
compatibility versions belong in `staticAttributes`.

`systemAttributes` are platform-written metadata. They are writable, but should
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
descriptor fields, not metadata revisions, not stale fences, and not admission
tokens.

`dynamicAttributes` is an allowlist of dynamic attribute names that this worker
is allowed to update. It is not the current value of those attributes.

Examples:

```text
dynamicAttributes = {
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
availability. Current usability still belongs to worker-runtime admission.

## Dynamic Attribute Boundary

Dynamic attributes are separated from `WorkerDescriptor` because each dynamic
attribute may need a different storage/index shape and update handler.

```text
heartbeat -> zset(workerId -> lastSeenTime)
battery   -> zset(workerId -> batteryLevel)
network   -> hash(workerId -> networkType)
load      -> hash / zset / bitmap, depending on policy
```

`WorkerDescriptor.dynamicAttributes` only decides whether an update is allowed:

```text
dynamicAttributes can accept or reject an update
dynamicAttributes cannot represent current attribute value
dynamicAttributes cannot replace worker score
dynamicAttributes cannot be used as direct scheduling truth
dynamicAttributes cannot prove worker availability
```

Dynamic attribute values are owned by attribute handlers:

```text
DynamicAttributeHandlerRegistry
  attrName -> built-in handler
```

The handler owns:

```text
payload validation
normalization
query storage/index shape
fast point-write behavior
```

## Dynamic Attribute Update Flow

```text
updateWorkerDynamicAttribute(workerId, attrName, payload, observedAt)
  -> read WorkerDescriptor
  -> require attrName in WorkerDescriptor.dynamicAttributes
  -> resolve built-in DynamicAttributeHandler(attrName)
  -> handler validate / normalize payload
  -> handler writes its own query storage/index
```

Dynamic attribute update is event-handler-like, but it is not a global event
bus. It must be bounded, point-write oriented, fast-fail, and idempotent where
possible. Routine high-frequency updates such as heartbeat, load, or network
observations must not emit global scheduling events by default.

Dynamic attributes are query/projection facts. A `heartbeat` dynamic attribute
does not require a second heartbeat owner. If adapter/session runtime already
has heartbeat or reachability information, the dynamic attribute handler may
point-read or project that existing fact for query use. It must not become the
worker availability truth.

If a dynamic attribute is relevant to scheduling policy, worker-runtime
admission may point-read the attribute owner's current value during validation.
Assignment-dispatch and worker score primitives must not interpret dynamic
attribute payloads directly, and dynamic attribute updates must not drive
worker availability by themselves.

## Scheduling Boundary

The stable model has three resource/handler relationships:

```text
Project / workload -> allowed WorkerGroups
Task                -> selected WorkerGroup
Task demand         -> WorkerDescriptor attributes inside selected WorkerGroup
Work / item / seed  -> EventHandler
```

`Project / workload -> allowed WorkerGroups` is operations configuration. It is
stable and should be resolved before task dispatch. Assignment-dispatch should
not query worker groups on every scheduling round.

`Task -> selected WorkerGroup` is chosen at task create/admission time from the
allowed set. It is exactly one group in v0 and immutable while the task is
running.

`Task demand -> WorkerDescriptor attributes` is worker matching inside the
selected worker group. Task demand may constrain targetWorkerId, placement,
static attributes, system attributes, or explicitly supported projected dynamic
attributes. These constraints narrow worker candidates; they do not replace
worker-runtime admission.

`targetWorkerId` is only a hard filter inside the task's selected
`workerGroupId`. It still must pass worker score acquire and worker-runtime
admission. It is not a worker group selector and not a transport target.

Dynamic attribute matching is deliberately narrow in v0. Assignment-dispatch
must not perform arbitrary dynamic-attribute multi-index queries. The default
path is worker-runtime admission point-reading the dynamic attribute owner's
current value. If a later executable spec needs candidate discovery such as
`battery > 20`, the dynamic attribute handler must expose a bounded candidate
index explicitly.

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
admission. It does not decide which worker group a task may use:

```text
project/workload binding
  -> task inherits workerGroupId
task eventCode
  -> validates against WorkerGroupDescriptor.eventCodes
pre-bound workerGroupId
  -> WorkerDescriptor static/system/query attributes filter and rank candidates
  -> worker-runtime admission validates current usability/capacity/reservation
```

Scheduling must not stop at descriptor matches. It reads worker-runtime owner
surfaces before producing a selected worker:

```text
task inherited workerGroupId -> worker score home bucket
worker score -> hot/recovery acquisition coordinate
worker-runtime admission -> current usability/capacity decision
assignment-dispatch -> selected worker + work claim + deliver seed
```

Descriptor metadata can be used by worker-runtime validation, policy mapping,
query views, and diagnostics. It is a candidate discovery / matching input; it
must not become a second admission owner.

In v0, admission is binary reservation of one scheduler-visible worker
resource. A selected worker is either reserved/admitted for the current
assignment or it is not. Do not model capacity pools inside
`WorkerDescriptor`. If a physical worker can handle multiple concurrent lanes,
represent those lanes as multiple logical workers until an executable spec
proves a real capacity owner is needed.

The worker score model remains separate:

```text
WorkerDescriptor
  metadata and dynamic attribute allowlist

WorkerScore
  HOT_ACQUIRE / RECOVERY_RECHECK acquisition coordinate

WorkerRuntimeAdmission
  validates metadata, optional dynamic query attributes, reachability, capacity,
  and reservation
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
handler refs carried by WorkerDescriptor
dynamic attribute current values inside WorkerDescriptor
adapter session / mailbox / connection state inside WorkerDescriptor
runtime score / dirty / admission fields inside WorkerDescriptor
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
the same worker needs different admission lane per group
```

Add public metadata revision/signature only when an executable spec proves a
stale-fence invariant that cannot be handled inside worker-runtime admission.

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
    system_attributes: Mapping[str, JsonValue]
    static_attributes: Mapping[str, JsonValue]
    dynamic_attributes: frozenset[str]
```

The Python executable spec may start with this shape directly. Runtime score,
admission, dynamic attribute indexes, and transport session facts should remain
separate owner structures.
