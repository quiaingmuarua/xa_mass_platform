# XA Mass JVM Runtime API Server

Status: current Runtime API, application admission and Server resource assembly.

## Responsibilities and API Reference

Within the [global behavior loop](../doc/kernel/scheduling-overview.md#system-behavior-model),
Server admits work, routes delivery and projects observations through existing
Owners. It owns the `/api/v1` boundary and error mapping, external Worker Identity,
Endpoint defaults and Prepare, Task/Direct Call waiting, finite Result export,
and Spring provider, Pacer and Adapter assembly. It does not select candidates,
interpret Matching queries, acquire Worker leases, claim Items, retry execution,
recover Workers, decide Task finality, route connections or execute Worker events.

`server_jvm` is an importable Java configuration library through
`XaMassServerConfiguration`, with no production main, Boot auto-configuration or
Scenario dependency. [Server Boot](../server_boot_jvm/README.md) owns the sole
production main, Boot JAR and profiles. Server's platform-only test bootstrap
supplies Boot infrastructure for tests and OpenAPI export. Scenarios consume only
the approved application services and DTOs described below. Server tests keep
independent test-only platform configuration; they do not copy Boot host profiles.

Read this Owner by use case:
[Projects and Tasks](#projects-tasks-and-results),
[Workers and Properties](#worker-preparation-and-properties),
[Delivery and Direct Calls](#delivery-and-direct-calls),
[assembly and lifecycle](#assembly-boundaries),
[configuration and health](#configuration), and [proof](#verification).

### Runtime Shape

```text
HTTP -> Controller -> Server admission/use case -> Kernel or Matching Owner
Pacer -> bounded Server observation handoff -> application projection
Boot -> Server providers/lifecycles -> configured Groups and managed Tasks -> Adapters
```

Kernel owns Task/Item data, Score and delivery resources; Matching owns Properties,
query admission and candidate resources. Server owns its external Identity HASH,
immutable Endpoint directory and instance-local Direct Call FIFO/waiters. Redis
operations remain in their Owner providers; missing JVM operations fail explicitly,
without an HTTP fallback or Server scheduler.

### API Reference and Response Contract

Routes, request/response fields, required values and schema bounds are maintained
in the [committed OpenAPI snapshot](../frontend/public/reference/openapi.json).
It covers Project reads, Task operations, Prepare, Runtime View, Platform Properties
PATCH and Worker Delivery. This README retains the behavioral rules that schemas
cannot express; in particular the generic Report payload does not describe event
admission, partial commits or loss semantics.

| Reference | Route |
| --- | --- |
| Live Scalar / OpenAPI | `/scalar`, `/v3/api-docs` |
| Read-only committed snapshot UI / JSON | `/api-reference`, `/reference/openapi.json` |
| Human architecture projection | `/overview.htm` |
| Diagnostic dictionary UI / JSON | `/reference/error-codes`, `/reference/platform-diagnostic-codes.json` |

Only `/api/v1/**` enters OpenAPI. Scalar telemetry, Agent Scalar and external fonts
are disabled. The live routes describe the running Server; the static projection
used by frontend/Vercel can lag until regenerated. After changing a Controller,
DTO, Tag or OpenAPI description, run `./gradlew :server_jvm:exportOpenApiSnapshot`.
The exporter reads an isolated `test` context on random loopback, removes the
request-derived `servers` field and canonicalizes the committed JSON. Contract
tests detect drift without rewriting it. Tags organize callers, not runtime
Owners or Redis domains.

After a public route matches, application outcomes use:

| HTTP status | Meaning |
| --- | --- |
| `200` | Completed, including idempotent no-op and bounded partial observation |
| `400 + ApiErrorResponse` | Input, business resource, precondition or state rejection |
| `429 + ApiErrorResponse` | Admission capacity exhausted; currently Direct Call only |
| `503 + ApiErrorResponse` | An Owner or required dependency is temporarily unavailable |

Business absence is `400` with a detailed numeric code. Successful values remain
use-case-specific: scalar, collection and Map bodies stay direct, while named DTOs
represent combined contracts, structured resources or cross-field invariants.
Do not add generic envelopes, `SimpleRequest` or one-field status wrappers.
Removed object envelopes are not compatibility formats. `ActionOutcome` represents
shared mutation effects (`applied | unchanged`); independent Item rejection remains
an entry in the append outcome Map, not a single-resource success. Whole-request
failure uses non-2xx `ApiErrorResponse` with code, stable default message and
requestId, never Owner reasons, Redis data or internal exception messages.

Framework failures remain coarse HTTP concerns (`404/405/415/500`) without XA
business codes. No platform operation declares business `404/409/422` responses.
Worker Delivery is the success-status exception: point Command poll returns `200`
or empty `204`, Report append returns `202`, and Adapter consume returns `200`;
its rejection still uses `400/503`. Route verification is an asynchronous
application port, not an HTTP endpoint.

Public error codes belong to Server; Adapter and Worker codes remain
producer-local. [Distribution](../distribution/server/README.md) generates the
packaged dictionary from compiled enums, excluding Scenario/capability errors,
without a cross-version stability promise. OpenAPI links to that dictionary
without copying it or assigning producer codes to API operations.

## Projects, Tasks and Results

Schema fields and request bounds are in the [API reference](#api-reference-and-response-contract).
For Task-ID operations, a missing Task returns `400/12002`; the wrong public
Task type returns `400/12008`.

| Operation | Admission | State effect | Failure boundary | Reference |
| --- | --- | --- | --- | --- |
| Project lookup | Configured Project; no default | Return the managed Group-to-Task mapping | Read never initializes resources | [Project directory](#profile-projects-and-managed-tasks) |
| Project Task list | Configured Project; `limit=1..1000` | Read a bounded directory page and independent Task/Score projections | Missing projections stay null; corruption fails | [Task Owner](../kernel_jvm/doc/resource-model/task-resource-model.md#cross-owner-creation) |
| Task create | Declared Project, one of its registered Groups, normalized supply and valid fields | Create a finite immutable Task descriptor | Validate before writes; unknown commit retains the generated identity | [TaskCreationService](src/main/java/com/xa/mass/server/task/TaskCreationService.java) |
| Approve / close | Finite Task only | Return the shared applied/unchanged effect | Missing/wrong-type rules above; Owner failure uses `503` | [TaskLifecycleService](src/main/java/com/xa/mass/server/task/TaskLifecycleService.java) |
| Ordinary Item append | Finite Task; structurally valid batch | Persist admitted Items without releasing idle park | Query rejection is per Item; whole-request failure is non-2xx | [TaskDataService](src/main/java/com/xa/mass/server/task/TaskDataService.java) |
| Item Call | Managed Task; all original queries admitted | Submit once, then observe within the wait bound | Submission may commit before `503`; observation limits yield `not_observed` | [Call sequence](#managed-task-call) |
| Result load | Finite or managed Task | Read stored `succeeded`, `failed` or `not_observed`; only success carries content | No inference from Score or repair of independent resources | [Result observation](#result-and-item-state-observation) |
| Item states | Finite or managed Task | Read decoded Score, independently of Results | Missing Items return null; Owner failure uses `503` | [Item Score Owner](../kernel_jvm/doc/score/task-item-score-band-scheduling.md) |
| Result export | Finite Task; one observed Task Score is `TERMINAL` | Stream only successful content through bounded Owner pages | Nonterminal: `400/12010`; overlapping generation: `400/12009` | [Result Owner](../kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md#taskitem-result-projection) |

### Profile Projects and managed Tasks

Managed Task provisioning belongs to configured Project/Group pairs, not
WorkerGroup registration. A Group may serve several Projects, each with
its own stable `PARK_WHEN_IDLE` Task. Public Task creation always uses
`CLOSE_WHEN_IDLE`. Project membership is passive Kernel data; all Tasks retain global scheduling
through the same Pacer. Pool/refill composition is unchanged.

`xa.mass.project-assembly.projects` is an immutable list of `{project-id,
worker-group-ids}` declarations. IDs are unique and each Group list is nonempty and
unique. There is no Project registration, update or deletion API, Redis registry,
dynamic Group association or default Project. Boot profiles own production values.

Startup initializes configured WorkerGroups, prepares and approves each Project's
managed Tasks, then starts Adapter ingress and scenarios. An absent Group or
conflicting Task fails startup. Completed stages survive failure; the next startup
uses the same coordinates and does not reset existing Scores, Items or creation
time. Managed IDs encode both UTF-8 coordinates independently with
Base64URL without padding, separated by a dot after `project-rpc-`. Clients consume
the returned mapping rather than calculating IDs.

Project Task pages are newest-first, with a one-member `truncated` lookahead and
no cursor or total. Closed Tasks remain listed; same-millisecond order follows the
Task Owner's reverse identity order. The read loads descriptors and decoded Score,
never Results or the global preview, and is not an atomic scheduling snapshot.
Physical directory definitions belong to the linked Task Owner.

### Task Admission

Creation has no request profile selector. Server generates `task-{UUID}` and
defaults priority to 50 and retry budget to 3. Optional `refill`
omission means `[]`; explicit null, retired `ruleId/refillTargets`, unavailable
Pools and invalid targets fail. Matching normalizes and MAX-merges equal Group/Pool
targets before complete immutable Kernel metadata is written, without inventory
reads, stock consumption, independent Matching writes or rollback.

Supply watermarks are shared hints, not Task-private quotas. Item queries name
functions independently: empty supply can use Identity, Phone or another Task's
stock, and closing a supplier neither clears stock nor unregisters functions.
The [Matching Owner](../worker_matching_jvm/README.md#item-queries-and-pool-maintenance)
owns function names, input shapes, bounds and Pool semantics; Server gains no
predicate interpretation, candidate selection, alternative search or lease authority.

Managed registration saves `[]` unless `xa.mass.task-rpc.refill-by-worker-group`
supplies complete RefillTarget JSON strings (Lab overrides remain 1000).
Re-registration compares the complete expected descriptor; lookup reads saved
declarations without resolving new defaults. Old Task layouts require a new scope,
with no migration, cleanup or dual reading.

Managed Tasks expose neither public lifecycle operations nor ordinary append.

Server stamps Item creation/expiry and captures each immutable selector using the
Task descriptor Group and named Matching function. Admission normalizes without
consuming candidates or creating refill demand. Missing selectors reject their
finite Item. Managed Call admission includes queries for duplicate IDs later
overwritten and for identity selectors.
Old direct selector Maps are rejected rather than converted to ANY; retained old
Items are unreadable. No query is inferred from supply declarations. See
[TaskDataService](src/main/java/com/xa/mass/server/task/TaskDataService.java) and
[TaskCallSubmissionService](src/main/java/com/xa/mass/server/task/call/TaskCallSubmissionService.java).

### Request-correlated creation and finite import

`TaskCreationService.createForRequest` is an application entry for App Checks; the
ordinary HTTP Task creation contract is unchanged. It derives `task-` plus SHA-256
from length-prefixed UTF-8 `["xa-mass/task-create/v1", projectId, requestId]`.
The scenario supplies a fingerprint of normalized creation inputs, excluding
first-created display defaults. Existing immutable metadata records the request
and fingerprint. A matching descriptor, Project membership and readable Task
Score returns the same identity across service restarts. Conflicts reject; partial
or unavailable observations retain that identity as unconfirmed without repair.
No new keys, request registry or scheduling authority are introduced.

`OperationGuard.taskMutation` shares one instance-local Task admission among file
import, ordinary finite append, approval and closure. Synchronous nested calls on
the owning thread retain the outer admission; another thread conflicts. The
existing general export guard remains non-reentrant. No distributed lock or
cross-Server guarantee is implied.

`TaskDataService.importFiniteTaskItems` admits at most 100 Items to a PRE_REVIEW
finite Task using Runtime default TTL. It reads existing Items and Score states,
skips matching complete identities without rewriting them, and rejects content
conflicts. Explicit re-import uses the original stored Item to initialize a
missing Score through the existing append operation. A Score without Item data
is unavailable, not reconstructed. Ordinary append semantics remain unchanged.
`loadTaskItems` supplies bounded 100-ID reads to business export without Redis
access from scenarios. None of these operations scans for substitute identities.

### Managed Task Call

The Server caches the immutable Task descriptor
(bounded, reset at capacity); a missing Task is not cached
and a removed Task still fails at Kernel submission. Concurrent calls to one Task
then use a per-Task group commit: a call finding no submission in flight submits
alone with no added wait, calls arriving meanwhile queue, and the queue head submits
the next batch for itself and the calls behind it before handing the lane on. A batch
keeps arrival order, holds at most 100 Items, never splits a call, and starts a new
batch before a call that repeats an earlier call's message ID, so repeated IDs keep
sequential semantics. The batch makes one Kernel Task Call submission (one idle-park
release before and after, one append) and one immediate Result read; every call
interprets only its own Item outcomes and observations, and a whole-batch failure
fails each call in it as it would have alone. Waiter registration is per call.

Waiters complete only from a stored-Result read by the probe thread. Pacer's
best-effort `ResultObservation` (after TASK success Results or Dispatch-terminal
failures are stored) makes matching in-process waiters due immediately, or probes
an in-flight Item again right after its current read; with no waiters it returns
without taking the registry lock. A lost notice, a Result stored by another
instance, or a failed read keeps the existing 50/100/250ms probe intervals.
Once submission is accepted it returns HTTP `200`;
each observed entry is `succeeded` or `failed`, while timeout, saturated
observation capacity, or Registry shutdown marks only the remainder
`not_observed` without inferring their runtime state. A Dispatch-terminal
failed Result completes that Item's waiter without a payload. It preserves all
immediately observed succeeded or failed entries.
Observation saturation does not return `429`. Duplicate Message IDs in one
request use the latest Item and produce one response entry. The caller can
later read the same Message IDs through the same Task-ID-scoped result route.

Task Call remains at-least-once. Submission spans existing owner operations,
so an Item write followed by an unconfirmed idle-park release repair can still return `503`.
Server does not retry or roll back that submission; callers should retain the
original Message IDs and reconcile them through `results:load` rather than
assuming every non-2xx response means no execution occurred.

### Result and Item State Observation

Call and Result reads use deduplicated Message IDs. A failed entry carries no
Worker payload or reason; late success may replace an earlier failed snapshot.
Each response is a read-time view, not an immutable historical event. Consequently `items:call` or
`results:load` may report `succeeded` while the independent Item Score remains
`ACTIVE` or `TERMINAL(tag=5)`. Score-based lifecycle, statistics and Runtime
projections continue to follow Kernel Score truth; Server neither coordinates
nor repairs the two resources.

`items:states` deduplicates IDs in input order, reads the descriptor and one
bounded Score observation, and exposes no raw Score or Result content. `active`
is tag 1; `terminal` is tags 2..9 and means scheduling ended, not frozen business
state. Score time is band-local, not a business event timestamp.

Task scheduling and Item observation lifecycles remain independent: retained Items
accept valid later observations after Task completion/closure without reopening
scheduling. This promises neither permanent retention nor recreation of deleted
Items. Server's application contract defines business finality.

Export reads [Result Owner pages](../kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md#taskitem-result-projection),
filters failed entries, deduplicates cursor observations by caller-owned messageId
and streams `application/x-ndjson`. One scan/temporary-file generation per Task per
Server process holds the overlap guard; it is released after generation, so
response transfers may overlap. Each line contains only messageId and unchanged
opaqueResultPayload, without an ordering contract. The temporary file is deleted
when the response stream closes, including failure paths. Server owns no Lab
input/output directory.

Terminal-only export is a scheduling admission rule, not a claim that business
outcomes are immutable. Later observations may still change a retained Item's
Result. Export reads pages over time rather than an atomic snapshot; another
export may therefore contain newer content.

### Later Task Outcomes

A Handler can retain the SDK's `WorkerOutcomeReporter` after returning its
execution Result. This TRACKED capability adds no Task mode or creation parameter.
Send success finishes execution and releases its original Worker lease. Later
observations affect only the same Item, including after Task closure or while the
Worker executes another Item. The fixed WORKER-to-TASK event
`platform.worker.task-outcome.observed` carries the original opaque `forward` and
JSON `{tag, observedAtMillis, opaqueResultPayload?}`. Server admits tags 6..9,
positive milliseconds within the Score Owner range, and optional nonblank string
content; it rejects extra fields. Kernel verifies the correlated Worker source.

The [Result Owner](../kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md#execution-and-observation-calls)
advances Score before optional content, orders content by tag then reported time,
retains content at equal timestamps and never erases it for state-only observations.
`results:load` repeatedly reads the
latest content without exposing its ordering fields; `items:states` reads Score
independently. `items:call` retains its existing Result wait and adds no
delivered/read/replied completion prerequisite. It returns the Result projection
available when observed, including newer content already present. Separate Owner
commits remain best-effort: an observation
may advance state without storing its content; there is no ACK, replay or repair.

### Task-backed Messages reads

Scenarios call the same application admission as HTTP, with the same bounds and
existing Task/Item/Result DTOs, never Controllers, providers, policy or mirrored
contracts. Do not add HTTP fallbacks, Runtime bridges or a generic Scenario
framework. Scenarios own neither HTTP waiters nor a Direct Call registry.
`TaskCallSubmissionService` supplies SMS with bounded managed submission and ordered
IDs without a servlet waiter; HTTP adds Result probing and wait registration.
Messages uses `TaskCreationService`, `TaskLifecycleService`, `ProjectTaskQueryService`
and `TaskDataService`; App Checks reuses TaskCreateResponse. Creation validates
Group, supply and numeric inputs before writes; finite append validates the input
batch before Owner operations while retaining per-Item semantic rejection.

Task names and immutable metadata belong to the Kernel descriptor, outside
scheduling config. `TaskCreationUnconfirmedException` retains the generated
identity when creation commit is unknown. Messages uses bounded Owner quantity
reads and one Result preview page of 100 with at most 100 Item reads; physical
Result storage belongs to the [Result Owner](../kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md#taskitem-result-projection). These are independent snapshots with existing data-error/503 behavior.
Business tag names and aggregation remain with the
[Messages Owner](../scenarios/message-campaigns-jvm/README.md); Server gains no
business state or Redis bypass.

## Worker Preparation and Properties

### WorkerGroup And Worker Preparation

| Operation | Admission | State effect | Failure boundary | Reference |
| --- | --- | --- | --- | --- |
| WorkerGroup register | Create-only attributes and Event Names | Create a declaration only; equivalent input returns `already_registered` | Different declaration returns `400/15006`, without update or Task creation | [WorkerGroupRegistrationService](src/main/java/com/xa/mass/server/worker/group/WorkerGroupRegistrationService.java) |
| Prepare / Prepare batch | Existing Group; valid identity coordinates; one kind and transport per batch | Resolve Identity, then Kernel Binding and absent cold membership | Only a complete ordered response is `200`; completed stages survive failure | [WorkerPreparationService](src/main/java/com/xa/mass/server/worker/preparation/WorkerPreparationService.java) |

Group attributes/Event Names are directory metadata, not Matching facts, Dispatch
evidence or per-Worker capability truth. Managed Tasks belong to configured
[Project/Group pairs](#profile-projects-and-managed-tasks).

`workerKind` is optional and defaults to `CLIENT_KEY`, preserving existing
ordinary callers. Android supplies `CLIENT_KEY` explicitly; ordinary Java may
omit it. `SCENARIO_LAB` derives its
Server-owned registration coordinate from immutable
`labInventoryKey + labInventoryLine` string properties (line parses as decimal
`1..100`, preserving the same registration key) and strictly rejects numeric
line values and `clientWorkerKey`; mutable fields such as `labSlot` do not participate in
identity. Both HTTP routes enter the same Server `prepareAll` path;
the single route supplies a one-item list. Server validates the batch shape and
every registration coordinate before side effects, then reads Group once and resolves the configured default Endpoint before any
identity write. Server resolves the identity batch, asks Kernel to create/read
actual Bindings, then initializes absent cold members. Calls are batch-bounded,
without per-Worker synchronous round trips or identity confirmation rereads;
Kernel storage and registration atomicity belong to the
[Worker Owner](../kernel_jvm/doc/runtime-redis/worker-runtime-redis-shape.md).
A normal Prepare uses four client commands including the Group read, while
Catalog registration uses two; this is a command budget, not throughput/latency
proof. Callers may retry the same derived coordinates. Failure does not imply
that only a prefix produced side effects.

`workerKind` only selects the Server-owned registration-key algorithm. It is
not part of the Redis key address: all identities for one WorkerGroup are
fields in the same Group identity Hash. Each algorithm emits a typed,
unambiguous field value, so an arbitrary `CLIENT_KEY` input cannot alias a
`SCENARIO_LAB` coordinate. The registration-key algorithms remain unchanged. This Binding layout cutover
requires a stopped, exact-scope rebuild; it has no compatibility reads. Retain
source Properties/configuration and recreate Groups, Workers and Tasks as
described in the [Worker Redis contract](../kernel_jvm/doc/runtime-redis/worker-runtime-redis-shape.md#scope-rebuild).

`CLIENT_KEY` requires non-blank `workerProperties.clientWorkerKey` and uses the
configured default Endpoint for registration.
Existing same-Group Binding wins over any changed default. Server validates the
returned actual Endpoint type and resolves its URI; it does not persist address
state. Kernel initializes only missing cold Score members and preserves every
existing Score. The HTTP field
`workerProperties` retains its existing shape, but Server consumes only the
selected identity policy's coordinates. Non-identity fields are neither
interpreted nor encoded or persisted as Matching facts. These are separate owners and
Redis keys, not one transaction; a repeated Prepare converges interrupted
stages. Ordinary Workers retain only their
Group/client key coordinate and never send a Worker ID hint. Transparent
reconnect reuses the current in-memory identity and Endpoint without preparing
again.

Prepare success does not imply connectivity, scheduling availability or observed
Properties. All Workers, including Polling, initially remain cold. Valid network
evidence may later request activation; evidence loss has no replay guarantee. New Workers
have no Matching facts until an admitted Adapter observation creates them;
Facts qualification belongs to
[Matching](../worker_matching_jvm/README.md#item-queries-and-pool-maintenance).
Explicit Any Pool and direct workerId need no Facts. Polling has no current Adapter
Properties path, so new Polling Workers use explicitly supplied Any or Identity. Existing stored facts remain readable
until a later complete observation replaces them; repeated Prepare never
overwrites them, Platform Properties, an active Worker lease or PAUSE.

### Runtime Worker Properties Admission

| Operation | Admission | State effect | Failure boundary | Reference |
| --- | --- | --- | --- | --- |
| Worker Properties observation | Only `ADAPTER -> SYSTEM platform.adapter.worker-properties.observed`; current Binding/Group admission | Replace the complete Worker Map, leaving Platform Properties unchanged | Invalid input is per-item; Facts infrastructure failure is `503` without cross-Group rollback | [WorkerDeliveryService](src/main/java/com/xa/mass/server/delivery/application/WorkerDeliveryService.java) |
| Platform Properties PATCH | Existing Matching facts; Prepare alone is insufficient | Patch only Platform fields; null deletes; return applied/unchanged | Before first facts: `400/15008`; Owner failure: `503/15011` | [WorkerResourceCommandService](src/main/java/com/xa/mass/server/worker/resource/WorkerResourceCommandService.java) |

The observation sourceId must match the path adapterId; diagnostics are
non-authoritative and forward must be empty. Payload contains exactly
`workerId + properties`; `properties` is a complete flat string KV Map. The full
encoded Report is bounded to 1,000,000 UTF-8 bytes. Mixed destinations or malformed
DTOs fail the whole HTTP batch before event admission. PATCH remains independent management, not a
read-only probe for Prepare.

The same `WorkerDeliveryService` reception use case collapses valid snapshots
by Worker to the last valid input in that HTTP batch, retaining input counts.
It reads Group and Endpoint in one bounded `WorkerResourceCatalog` Binding
read, rejects unknown/unbound/wrong-Adapter Workers, groups by Group, and calls
`WorkerProperties.upsertWorkerFactsBatch` directly. No intermediate
resource mutation service or separate Properties Report API participates.
Prepare and registration do not participate. After each Group facts write,
APPLIED members alone request one bounded Score invalidation through the
existing `WorkerSchedulingService`; UNCHANGED members add no Score command.
APPLIED/UNCHANGED accepts all valid inputs collapsed into that Worker; other
mutation outcomes reject them. Infrastructure failure uses operation
`workerDelivery.appendAdapterPropertiesReports` and the Properties-unavailable
code; SYSTEM drops that failed batch instead of retrying it.

An APPLIED Platform patch requests the same Score invalidation with one Worker;
it never modifies the Worker-owned Map.

Matching owns the persistent facts, not Server. Replacement removes omitted
keys without retaining registration fields inside Properties; independent
identity, Binding and Worker records remain intact. Facts validation, index
maintenance and storage atomicity belong to
[Matching](../worker_matching_jvm/README.md#facts-writes-and-index-maintenance).
Server requests invalidation through `WorkerSchedulingService` without waking
scheduling. Exact Score changes, cold/current/future protections and subsequent
candidate requalification belong to the
[HOT lease protocol](../kernel_jvm/doc/score/worker-hot-acquire-lease-protocol.md#properties-and-network-evidence).

Facts commit before Score invalidation. Execution acquisition can win in between;
its future hold then survives invalidation and keeps its result-release fence.
Invalidation failure preserves the successful facts response and emits an aggregate
diagnostic. An UNCHANGED retry does not replay invalidation. There is no ACK,
outbox, property-version transaction or background repair. Dispatch requires its
own TRANSITIONED execution acquisition, never an inferred state observation.

Concurrent observation batches have no timestamp/version
fence; effective storage writes determine facts. A failed first publication can
leave no facts, and a failed later publication can leave old facts. A later
explicit complete report or connection baseline can supply new input. No
quiet-network eventual repair, ACK, throttling or publication history is provided.
If a full observation was installed in Adapter but lost upstream, a later
complete publication may include the lost changes. Adapter cache, connection
baseline and publication rules belong to its
[Worker Properties Projection](../transport/netty-adapter/README.md#worker-properties-projection).
Server never merges an incoming snapshot with its older facts. This path does
not repair Worker-to-Adapter input loss or guarantee that the Adapter cache
always equals the latest Host state.

### Runtime Views and Scheduling Control

| Operation | Admission | State effect | Failure boundary | Reference |
| --- | --- | --- | --- | --- |
| Group batch-get / Preview | Explicit bounded IDs / one bounded sample | Read directory projections in request order / sampled order | Unreadable sampled rows are counted and omitted; no completeness promise | [RuntimeViewService](src/main/java/com/xa/mass/server/runtimeview/RuntimeViewService.java) |
| Task Preview | Bounded top Score coordinates | Compose stored descriptor, supply and Score Band | Missing descriptor stays null; corruption returns `503`; no repair or Pool fallback | [RuntimeViewService](src/main/java/com/xa/mass/server/runtimeview/RuntimeViewService.java) |
| Worker Preview | One named Group; bounded sample | Join independent Binding and Properties observations | Missing Kernel descriptor is unreadable; no usable facts has the projection below | [RuntimeViewService](src/main/java/com/xa/mass/server/runtimeview/RuntimeViewService.java) |
| Scheduling observe | Bounded identities; complete Owner result | Serialize Kernel's decoded states and shared read time | Owner failure is unavailable, never an invented state | [WorkerSchedulingService](src/main/java/com/xa/mass/server/worker/scheduling/WorkerSchedulingService.java) |
| Pause / resume | Kernel-owned resource/state admission | Map APPLIED/UNCHANGED to ActionOutcome | MISSING/CONFLICT use public `15008..15010`; Owner failure uses `503/15004`, without Kernel reasons | [Pause/resume Owner](../kernel_jvm/doc/score/worker-hot-acquire-lease-protocol.md#pause-and-recovery) |

Preview has no cursor, total, stable order or completeness meaning. Task supply
comes from saved descriptors, not current Pool stock or function availability;
its Score order is neither business priority nor execution evidence. Reads never
create, approve, close or repair Tasks and expose no raw Score. Worker Preview's
Binding and Properties reads use batches of at most 100 without an atomic join.

Worker Preview still returns the Kernel-owned identity, Group and Endpoint when
Matching returns no usable facts. Both Properties fields are then empty Maps,
and the Worker counts as returned rather than unreadable. This is a projection,
not a synthetic Matching record or proof of an observed empty baseline. The
current `WorkerProperties` observation maps both missing and undecodable facts to no usable facts;
this read distinction is unchanged. Missing Kernel descriptors still count as
unreadable, and Owner call failures still fail the request.

Scheduling observation remains independent of Adapter connections, Binding and
Task execution evidence. Server validates completeness but owns no decoded fields,
slot arithmetic or state classification. Pause/resume is separate from
[Properties admission](#runtime-worker-properties-admission), whose invalid-change
or state-conflict errors also stay within public `15008..15010`.

The complete observation preserves requested identity order and exposes no raw
Score or knowledge of the active Pacer's HOT eligibility epoch.
`hot-score-overdue` means only that a positive HOT Score precedes the current
10ms Worker Score slot; it is weaker than the Kernel's floor-aware candidate range.
Provider failure returns the existing Runtime View unavailable error rather
than inventing a Worker state.

## Delivery and Direct Calls

### Worker Delivery

| Operation | Admission | State effect | Failure boundary | Reference |
| --- | --- | --- | --- | --- |
| Adapter Command consume | Valid bounded consume request | Destructively take a bounded response, ordered as below | Consumed Commands are not waiter-owned retractable state | [WorkerDeliveryService](src/main/java/com/xa/mass/server/delivery/application/WorkerDeliveryService.java) |
| Adapter Report append | Valid DTO batch with one supported destination | Route to the named semantic Owner and return counts | Oversize/mixed/unsupported batches fail before effects; semantic failures remain per-item | [Routing below](#worker-delivery) |
| Point Command poll | Catalog Binding verifies | Observe polling best effort, then consume | Evidence queue/append failure does not change the poll result | [Polling contract](#endpoint-defaults-and-polling-evidence) |
| Point Report append | Matching Worker producer and admitted TASK event | Hand off TASK evidence | Producer/event rejection does not enter another lane | [TASK events below](#worker-delivery) |

TASK Reports are classified as execution success, execution failure or outcome
observation. Each nonempty evidence-type subset enters its Kernel lane with one
list write, in that order; a later append failure does not roll back earlier writes.
Destinations select TASK Result, SERVER Direct Call or KERNEL Serviceability
handling.
SYSTEM is a platform-event destination, not Direct Call correlation. Its fixed
`platform.adapter.worker-properties.observed` event enters the Worker resource
use case; unknown events are individually rejected. Even a matching Direct Call
`forward` cannot complete a waiter via SYSTEM.
Direct Call Commands use `src=SERVER`; their Worker/Adapter replies target SERVER.
The Direct Call registry checks the pending correlation, Adapter and target
producer before accepting a reply; TASK ingress follows the separate rules below.
Adapter queue capacity is independent of the HTTP batch-size bound.
A failed or incomplete TASK evidence append, or failure to admit the complete
valid KERNEL evidence subset, returns `503` for the existing upstream retry path.
All Report destinations share this path; adding a destination adds no endpoint.

Server owns the Worker Delivery HTTP and owner-provider composition. It
constructs active Adapters only through the finite public Netty factory.

Adapter Command consume and Report append use the loopback Worker Delivery HTTP
boundary. First route verification uses a Server-injected asynchronous port:
one bounded Server queue is drained by one resident virtual thread, and current
Catalog Binding snapshots are read asynchronously in batches of at most 100 IDs
before the individual Adapter requests complete. The queue is transient coordination, not Route
or Binding truth. Adapter lifecycle, schedulers, queues, current route registry
and physical Channels remain owned by `transport/netty-adapter`.

Long-lived Worker identity carries `workerId` in the Report source and exact
`null` payload. Adapter routing and retained verification use only workerId;
WorkerGroup remains outside the Transport route. Kernel Serviceability writes
Adapter-partitioned probe requests in every preset: `DEFAULT` rechecks due
RECOVERY Workers, while the other presets also enable HOT probes. The
[Serviceability policy](../kernel_pacer_jvm/doc/dispatch/worker-serviceability-scheduling.md)
owns these preset differences. Server
destructively consumes a bounded request set only at the lowest Command-response
priority and constructs one `KERNEL -> ADAPTER`
`platform.adapter.worker-connections.snapshot` Command. The ordinary Adapter
Result path routes all `ADAPTER -> KERNEL` Reports into the bounded Kernel
Serviceability evidence handoff. This includes periodic snapshots and
Adapter-produced single-Worker Route changes or TASK delivery-expiry evidence.
Server parses neither event nor payload semantics, does not resolve
WorkerGroup, and never invokes the Worker score owner.
These Kernel-owned best-effort handoffs are not current connectivity truth;
Pacer consumes the evidence and owns its Score policy.

For `dst=TASK`, Worker Delivery checks producer type and exact event contracts
before mapping to the Kernel-owned lanes. WORKER plus
`platform.worker.command.succeeded` maps to `TaskEvidenceType.EXECUTION_SUCCESS`;
WORKER plus `platform.worker.command.failed` maps to FAILURE. Path-matching
ADAPTER plus `platform.adapter.command.delivery-failed` also maps to FAILURE
only with exactly `{"workerId":"...","reason":"DEADLINE_EXCEEDED"}`.
WORKER plus `platform.worker.task-outcome.observed` maps to OUTCOME_OBSERVATION
after the [strict outcome payload admission](#later-task-outcomes). Other
event/producer combinations are rejected. Polling point results additionally
verify the path Worker's Binding and require its identity in `sourceId`.
Adapter-batch Worker execution Reports do not perform that Binding lookup or
compare `sourceId` with the Worker encoded in `forward`. Server never parses the
opaque ResultContext. Kernel Result Routing decodes `forward` and uses its
identities and fences for execution evidence, without comparing its Worker ID
to the Report source; later outcome observations explicitly check that identity
match. The selected execution lane is not reclassified by Kernel.
`diagnosticCode` is required string diagnostics, allows empty and arbitrary
values, and never controls acceptance, classification or correlation. Adapter
delivery-expiry still emits a separate `dst=KERNEL`
Serviceability report through its separate homogeneous Report batch.

Server-level route verification defaults to a `100000` request queue and a
`5s` Binding-read timeout. Queue rejection, timeout, shutdown, or Binding-owner
failure completes affected verification requests exceptionally; Server does
not retry them. Worker connection retry remains the recovery owner.

Server binds complete Adapter configuration and checks its corresponding Endpoint;
retention, cache budgets and visibility belong to the
[Adapter Properties Owner](../transport/netty-adapter/README.md#worker-properties-projection).
Properties snapshot Direct Calls are opaque to Server and are separate from
connection snapshots, with no Server join or freshness rule. Cache installation
produces SYSTEM observation, never KERNEL Properties evidence.

### Direct Calls and Network Observation

| Operation | Admission | State effect | Failure boundary | Reference |
| --- | --- | --- | --- | --- |
| Adapter Direct Call | One configured Adapter; top-level opaquePayload | Use the instance-local FIFO and correlate the selected target | Bounded capacity can return `429` | [DirectCallService](src/main/java/com/xa/mass/server/delivery/directcall/DirectCallService.java) |
| Worker Direct Call | One Adapter and Group; 1..100 workerId-to-payload entries currently bound to it | Offer caller-targeted Commands and correlate replies | `command-slot-occupied` rejects; timeout/cancellation/shutdown ends waiting without retraction | [Mailbox sequence below](#direct-calls-and-network-observation) |
| Network observe | One Adapter; bounded Worker IDs | Use Direct Call to project connected/disconnected/unknown | Timeout, rejection or malformed payload is unavailable, never synthetic unknown | [RuntimeViewService](src/main/java/com/xa/mass/server/runtimeview/RuntimeViewService.java) |

The two Direct Call request modes are exclusive; Server never partitions one call
across Adapters, creates Task/Result Routing truth, or selects substitute targets.

`messageType` and each opaque payload pass through unchanged. Server does not
enumerate event support or convert an unknown event into an HTTP admission
error; the Adapter (`23005`) or Worker (`3302`) returns an observed execution
result event: `platform.adapter.command.failed` or
`platform.worker.command.failed`. This use case has no Server execution whitelist.

Direct Call completion matches the exact Worker or Adapter command success/failure
event for the pending target, not the original Command name. Existing forward,
Adapter, producer, deadline and completion guards remain. Properties and
connection observations cannot complete a waiter. An observed target exposes
`messageType + diagnosticCode + opaqueResultPayload`; unobserved/rejected targets
expose only status/reason. Observed means a result was received, not success.
Network observation requires the Adapter success event before decoding its
known snapshot output. No extra execution-status field is introduced.

Worker calls neither require pause nor read Score. Server uses the non-overwriting
offer of [WorkerCommandRuntime](../kernel_jvm/src/main/java/com/xa/mass/kernel/delivery/WorkerCommandRuntime.java).
The authoritative TASK append may replace an offered Direct Command until
destructive consumption. Physical storage belongs to the Kernel
[delivery keyspace](../kernel_jvm/doc/runtime-redis/redis-keyspace.md).

Adapter-targeted calls enter a bounded Server-memory FIFO. Adapter Commands are
consumed first; any remaining response capacity is filled by exactly one
bounded consume through WorkerCommandRuntime. If capacity still remains,
Server may consume up to 100 coalesced Kernel Serviceability requests and add
one Adapter snapshot Command. Only a Worker Command map key is its workerId;
Adapter and Kernel Command keys are response-local and opaque.

Network observation sends `platform.adapter.worker-connections.snapshot` through
that same FIFO, waiter and correlation; it creates no Server copy of Route truth.
`readAt` is Server observation time.
The caller groups Workers by `endpointManagerId`; Server does not join Adapter
Network with Binding, WorkerGroup, scheduling or execution state.

### Endpoint Defaults and Polling Evidence

Every configured transport type requires one explicit default, while multiple
WebSocket or Socket Endpoints of that type may remain addressable:

```yaml
xa.mass.worker-endpoints:
  defaults:
    POLLING: system-polling
    WEBSOCKET: adapter-a
  endpoints:
    system-polling:
      transport-type: POLLING
      public-uri: http://127.0.0.1:18082
    adapter-a:
      transport-type: WEBSOCKET
      public-uri: ws://127.0.0.1:18083/api/v1/worker-delivery/websocket
```

No Worker-ID hashing chooses the default. A changed default does not migrate
existing bindings. Unknown or wrong-type defaults fail startup. The old config
prefix has no alias. Prepare HTTP requests and responses remain unchanged.
`WorkerEndpointDirectory` binds this configuration directly as one immutable
address model and owns no Redis connection.

Each valid point poll verifies Catalog Binding, then best-effort appends
`platform.server.worker-poll.observed` from SERVER/system-polling to KERNEL
before Command consumption. Server creates the timestamp. Empty polls count;
queue capacity or append failure drops observation without changing the poll
result. Public Adapter ingress rejects forged SERVER observations. All presets
consume the shared evidence lane; DEFAULT adds RECOVERY-only rechecks. No Server
dedup cache, activation ACK or replay is installed.

## Assembly Boundaries

Production code separates HTTP adapters (`api.v1.controller` / `contract`), Task,
Worker and Delivery use cases, Runtime View, and `assembly` provider/lifecycle
wiring. Those packages add no alternate Runtime Owners. Only assembly selects
providers and imports the public `KernelPacerRuntime`.

The Pacer candidate port is `WorkerMatching`; Server admission uses Catalog and
Properties separately. Server injects MatchingComposition's network-evidence
filter method into Pacer; Pacer owns Binding/source checks and Result routing,
Matching owns timestamp persistence, and Kernel owns Score effects. The
[application assembly Owner](../kernel_pacer_jvm/doc/application-assembly.md)
defines policy composition and internal lanes.

### Kernel Providers

Controllers and use-case services depend on `kernel_jvm` and
`worker_matching_jvm` owner contracts.
Provider selection, construction and destruction stay in Server Spring
assembly. The `assembly.redis` package owns connection and health only;
Redis key operations live in
owner-local provider packages.

`assembly.matching` registers one `MatchingComposition` lifecycle Bean. It exposes
stable `WorkerMatchingCatalog` and `WorkerProperties` interface Beans with independent
destruction disabled. Task admission uses Catalog; Properties reception, Platform
mutation and Runtime Facts display use only `WorkerProperties`. Pacer still sees
only `WorkerMatching`. Public Properties behavior is defined in
[Runtime Worker Properties Admission](#runtime-worker-properties-admission).
Composition owns one lazy Matching Redis connection. Failed assembly and
Composition destruction close it idempotently without shutting down the
Server-owned RedisClient. Server does not assemble separate index lifecycles.
Resource dependencies, startup retention and Facts/index writes belong to the
[Matching Owner](../worker_matching_jvm/README.md#fixed-resource-composition).

The [Prepare use case](#workergroup-and-worker-preparation) composes Server Identity
and Kernel Binding/Score operations independently of Properties admission.

### Worker And Scenario Assembly

The default profile declares no Scenario Group and starts no Adapter. Explicit
configuration initializes create-only Groups, prepares/approves Project/Group
managed Tasks, then starts Adapter ingress and scenarios. Boot owns the
[profile and page assembly](../server_boot_jvm/README.md#platform-and-preview).
Scenarios consume prepared Projects;
frontend availability does not enable business resources.

App Checks may import only Matching
`FixedWindowPoolDefinition` and its `WindowLimit` value for startup declarations.
Messages may import only `QualifiedCountryDefinition` for its Worker qualification,
Pool consumer and independently enabled Phone query. `WorkerMatchingConfiguration`
collects both definition Bean lists separately and passes them to
MatchingComposition; these Beans depend only on configuration or static bindings, never Task services
or the running Matching instance. Scenario code cannot access Matching stock,
storage, policy implementations or lifecycle. No Server DTO mirrors the definition.
The existing Group enablement and Project initialization remain profile-owned.
Missing declarations for enabled functions fail startup without a business fallback.

Server never parses Worker files, creates business Definitions or manages a
Worker process; that belongs to the standalone
[Simulator Host](../worker_simulator_jvm/README.md). Products create no Redis clients
or platform loops and stop before platform resources. Scenario/Server shutdown
never clears the Redis scope. Failed initialization fails
startup and destroys already-created resources, including the Adapter host.
[Boot](../server_boot_jvm/README.md#platform-and-preview) owns composition;
[distribution](../distribution/server/README.md) owns packaging and launch.

### Worker Allocation Observations

Pacer supplies the same five-field `KernelPacerRuntime.WorkerObservation` record
directly to the Server assembly: `workerGroupId`, immutable `workerIds`,
`observedAtMillis`, `messageEventName` and `observationEventName`. Source time is
sampled after successful Worker acquisition and Item claim; `worker.assigned`
precedes Command encoding/publication. It is not an execution/Result count or an
eventual-consistency promise, and carries no Task, message ID, Project, Endpoint or
Score correlation.

`WorkerObservationConsumer` owns one standard queue of 256 whole DTO batches and
one consumer thread. A drain handles at most 16 batches. Queue saturation drops
the current whole batch, without delaying or changing dispatch. There is no retry,
replay or deduplication. With no registered handlers it allocates
neither queue nor thread, and Pacer receives a no-op sink.

`KernelPacerConfiguration` explicitly assembles a deeply immutable
`Group -> EventKey(messageEventName, observationEventName) -> List<FunctionHandler>`
table. Selections match exact strings. Different handlers may share a selection;
registering the same instance twice within one selection fails assembly. No prefix,
wildcard, dynamic registration or handler identifier is used. Unmatched notices
are ignored before queue admission without waking the consumer, reading Facts or
changing diagnostics. Only matched notices contribute to enqueued/dropped counts;
matched notices received while stopped are dropped. Each notice enters the queue
once, regardless of handler count.

The package-local `FunctionHandler.handle(List<WorkerObservation>)` receives a
nonempty immutable list. Each drain calls each matching handler instance once,
with its selected notification subsequence in receipt order, including duplicates.
The list may span Groups and event selections. Handler execution follows first
appearance in the drain; a shared notice uses registration-list order. Calls remain
serial on the consumer thread, with no transactional or cross-handler dependency
guarantee. A handler's ordinary runtime exception counts as one processing failure
and does not prevent the remaining handlers. Effects are not rolled back or replayed.
The receiver has no Properties dependency; handler-owned operations run outside
its short admission/lifecycle gate.

One `PlatformPropertiesHandler` serves all `WorkerPropertyProjection` definitions.
Definitions provide a pure `(current Platform Properties, observation times) ->
local patch` function; duplicate projection selections still fail assembly. The
handler groups selected times by Group/Worker, chunks reads at the existing
`WorkerProperties` read budget, and computes against immutable input snapshots.
For each Worker, projections run in their first-appearance order within the drain.
Each projection's time list retains notification receipt order without sorting or
deduplication. Thus `A(10), B(20), A(30)` invokes `A([10,30])` then `B([20])`, not
the original event interleaving. Projections must not depend on cross-selection
event replay. Later projections see preceding local patches and overwrite any
overlapping fields. This single handler issues at most one
`WorkerResourceCommandService.patchPlatformProperties` per Worker in that drain.
Unreturned fields remain unchanged; null retains the existing patch deletion meaning.

Missing/unreadable Facts are skipped without initialization. Failed reads skip the
affected page; computation/patch failures skip that Worker and retain earlier
successful writes. One consumer serializes this local read/modify/write path;
concurrent external writes to the same fields can still overwrite observations.
There is no counter Lua, CAS, persistent queue, flush or recovery scan. Existing
APPLIED patch handling still performs separate best-effort candidate invalidation.
The notification path does not make eligibility decisions. A declared
[Matching fixed-window Pool](../worker_matching_jvm/README.md#fixed-window-pools)
can read the projected fields during subsequent refill. App Checks supplies both
the Pool definition and projection from the same Pool/Group configuration; Server
collects definitions without interpreting their fields or window settings.

The consumer starts before Pacer and stops after it, before Matching destruction.
Stop closes ingress, discards queued batches, interrupts and waits up to 5 seconds
for the current processing call; repeated close uses the same deadline. Failed
startup cleans any started consumer. Only the receiver modifies the assembly's
shared running flag. The Properties handler reads it at the existing read/Worker/
write boundaries, so a read returning after stop does not start the next write.
Stop also prevents subsequent handler calls; already-started operations cannot be
revoked. Handlers have no separate lifecycle or shutdown resources.
Optional `xa.mass.WorkerObservation` JFR
summaries contain cumulative enqueued/dropped batch counts and processing failures
only, without business content or tracing identities. Fan-out does not multiply
enqueue/drop counts; Properties failures retain their existing page/Worker counts
through the receiver's shared failure counter.

Scenario code receives only the Server's pure projection contract, without a
Pacer or FunctionHandler dependency. Its first projection is the
[App Checks assignment window](../scenarios/app-checks-jvm/README.md#分配窗口投影).

### Pacer Lifecycle

Result Convergence starts before Dispatch Convergence; Worker/Adapter assembly
starts only after the aggregate reaches `RUNNING`. Shutdown reverses that order
within one shared deadline, and failed start rolls back every already-started
application. Server delegates to
[KernelPacerRuntime's lifecycle](../kernel_pacer_jvm/doc/application-assembly.md#lifecycle);
it does not host another scheduler. Observation-consumer ordering and its own
bounded shutdown are specified above.

## Configuration

Default application limits; deployment coordinates are maintained by
[the executable configuration](../server_boot_jvm/README.md#pages-and-configuration):

```text
Managed Task Call wait         30s default / 60s maximum
Task Call waiters              10000 maximum
Task Call observations         100000 pending waiter-message associations
Task Call Probe batch          256 due message IDs per round
DIRECT_CALL wait               3s default / 10s maximum
Adapter Direct FIFO capacity   1000 per Adapter
Pending Direct targets         10000 per Server
Serviceability probe requests  10000 per Adapter handoff
Serviceability evidence       10000 per Redis scope
```

Spring Profile controls assembly while `xa.mass.redis.scope` controls the data
boundary; the Redis DB number is not a profile or test discriminator. Scope
syntax and the complete physical ABI are owned by the Kernel
[Redis Keyspace contract](../kernel_jvm/doc/runtime-redis/redis-keyspace.md).

The Adapter default section supplies remote API connection defaults only; an
instance requires an explicit declaration and matching Endpoint directory entry.
Endpoint configuration and Polling rules are maintained once in
[Endpoint Defaults and Polling Evidence](#endpoint-defaults-and-polling-evidence).

`xa.mass.redis` is the production Redis URL/scope source. `xa.mass.kernel-pacer`
binds `enabled`, `preset` and `shutdown-timeout`; environment or command-line
preset overrides use normal Spring binding, and unknown presets fail before
Runtime construction. Server forwards dependencies, observation sink and settings;
[Pacer](../kernel_pacer_jvm/doc/application-assembly.md#production-configuration)
owns preset policy. The proof-only preset is rejected outside `test_*` scopes.
Production profile choices belong to Boot.

Exactly one Server per Redis scope may enable Pacer; API-only replicas must set
`xa.mass.kernel-pacer.enabled=false`. There is no distributed leader election.
The Boot-supplied [assignment ceiling](../server_boot_jvm/README.md#assignment-batch-ceiling)
is bound and forwarded by `KernelPacerProperties`; Pacer owns its admission and
Item/refill policy, while Matching retains its separate call limit. Server creates
no scheduling budget or cross-module batch contract.

### Outcome Names and Scope Compatibility

`TaskItemOutcomeProperties` owns the application meaning of terminal tags.
Dispatch exhaustion/expiry uses 5 (`failed`), and execution success uses 6
(default `succeeded`), supplied through the sole Pacer assembly entry. Configure
`xa.mass.task-item-outcomes.names` to name tags 6..9, for example
`{6: sent, 7: delivered, 8: read, 9: replied}`. Names must be nonblank and unique,
including the reserved name `failed`; 5 cannot be configured. Tags 2..4 remain
legal unnamed terminal states. This is startup configuration, with no state
management API or persistent name directory. Configuring a name installs no
business observation event or handler.

TaskItem outcome deployment uses a stopped-scope rebuild. Stop every process
using the explicitly selected scope, clear only that exact scope with
`SCAN` plus `UNLINK`, then rebuild its Groups, Workers and Tasks. Without a named
scope, no non-test data is cleared. Old tag 9 is mechanically terminal but must
not be interpreted automatically as the newly configured replied state. There are no
compatibility reads, background migration or repair loops. Proofs use unique
`test_*` scopes.

### Run

The Server library has no production main or Boot tasks. Use the
[executable startup commands](../server_boot_jvm/README.md#run) or the
[Runtime distribution](../distribution/server/README.md). Configuration binding,
provider construction and bounded resource shutdown remain in this library.

Health endpoints:

```text
GET /actuator/health/liveness
GET /actuator/health/readiness
```

Liveness covers the JVM process. Readiness requires Worker Matching, Result
Convergence, Dispatch Convergence and Kernel Redis to remain available.
Serviceability is part of Dispatch Convergence in every preset, with no separate
lifecycle. The `kernel` health contributor exposes only the aggregate
lifecycle and the two Java convergence-application states; `workerMatching`
reports its bounded consumer state separately.

Known readiness mismatch: `KernelPacerHealthIndicator` currently reports DOWN
when that lifecycle is disabled, including the API-only replica described
above. The readiness/deployment contract remains unresolved; disabling Pacer
must not be documented as making such a replica readiness-UP. This documentation
correction does not change the indicator or its tests.

### Diagnostics

Optional call diagnosis uses `xa.mass.diagnostics.enabled=true` plus explicit
JFR event settings. The default has no HTTP diagnostic Filter or executor
sampler. Server-owned events time Direct Binding reads, mailbox offers and
Command consumption, Task Call submission and immediate/probed Result observation
without additional Redis operations. Task RPC observations include waiter
admission, each activated batch's maximum due-item lateness, probe batch/hit counts and deduplicated observation,
timeout, cancellation and shutdown. The servlet filter also covers `items:call`.
They carry fixed
stages, counts and failure flags, never Worker identity, payload or results.
The servlet observation registers its listener during `startAsync`, records
initial execution separately, and emits at most one completion after timeout,
error or normal completion. Servlet completion is distinct from client receipt.
HTTP executor snapshots use JFR's periodic lifecycle and are removed on context
shutdown; Server creates no sampling thread. Platform-pool metrics are
inapplicable for virtual-thread execution. Diagnostics do not change admission,
callback ordering or failure classification. The finite measurement and safe
export contract belongs to [Call Performance](../integrations/worker-call-performance/README.md#jfr-diagnostics).
Sampled Task correlations are stateless SHA-256 identifiers at 1/64; no identity
or payload is a metric label. Offline joins stay inside one Server JVM, explicitly
retain incomplete/overlapping evidence and do not infer TaskItem finality.

## Verification

[TESTING](../TESTING.md#lane-index) owns lane selection, commands, dependencies
and CI routing. Registered claims and limits are in
[JVM Contracts](../doc/testing/proof-registry.md#jvm_contracts),
[Redis Owner](../doc/testing/proof-registry.md#redis_owner) and
[Runtime Boundary](../doc/testing/proof-registry.md#runtime_boundary).
The Server-specific test entrypoints are:

| Boundary | Focused evidence |
| --- | --- |
| Project/Group initialization and directory reads | [ProjectTaskInitializerTest](src/test/java/com/xa/mass/server/project/ProjectTaskInitializerTest.java), [ProjectTaskQueryServiceTest](src/test/java/com/xa/mass/server/project/ProjectTaskQueryServiceTest.java) |
| Independent Prepare stages | [WorkerPreparationServiceTest](src/test/java/com/xa/mass/server/worker/preparation/WorkerPreparationServiceTest.java) |
| Call batching and finite export | [TaskCallSubmissionBatcherTest](src/test/java/com/xa/mass/server/task/call/TaskCallSubmissionBatcherTest.java), [TaskResultsExportServiceTest](src/test/java/com/xa/mass/server/task/result/TaskResultsExportServiceTest.java) |
| Report admission and partial effects | [WorkerDeliveryServiceTest](src/test/java/com/xa/mass/server/delivery/application/WorkerDeliveryServiceTest.java) |
| Observation queue, projections and stop | [WorkerObservationConsumerTest](src/test/java/com/xa/mass/server/assembly/pacer/WorkerObservationConsumerTest.java), [PlatformPropertiesHandlerTest](src/test/java/com/xa/mass/server/assembly/pacer/PlatformPropertiesHandlerTest.java) |
| Actual protocol traversal and HTTP execution | [RuntimeBoundaryIntegrationTest](src/test/java/com/xa/mass/server/integration/RuntimeBoundaryIntegrationTest.java), [DirectCallHttpExecutionIntegrationTest](src/test/java/com/xa/mass/server/integration/DirectCallHttpExecutionIntegrationTest.java) |
| Lost-disconnect expiry/reconnect witness | [OfflineTaskDeliveryRuntimeBoundaryTest](src/test/java/com/xa/mass/server/integration/OfflineTaskDeliveryRuntimeBoundaryTest.java) |

The lost-disconnect proof waits for a natural RECOVERY recheck before reconnecting.
CONNECTED retains the future recheck coordinate, so its completion observation allows
30 seconds: DEFAULT's 15-second hold plus the ordinary 15-second observation budget.
It neither changes the production delay nor injects Score writes; the same Item must
still complete through actual Worker execution and Result handling.

Ordinary Server tests use explicit test-only platform configuration with Pacer
disabled and unreachable Redis, so a local Redis cannot hide accidental assembly
connections. Named integration fixtures enable the real Owners and Pacer; their
injection order, execution-pool variants and timing budgets remain in the linked
tests. Lost-disconnect failure evidence is bounded Command/evidence timing and
Score transitions, never opaque Worker content.
