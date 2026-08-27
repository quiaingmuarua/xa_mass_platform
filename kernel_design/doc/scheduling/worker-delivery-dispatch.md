# Worker Delivery Dispatch

Status: active mechanism contract.

Worker Delivery carries already-assigned work. It does not select Workers,
claim TaskItems, mutate scores, or decide result truth.

The control and connection vocabulary is deliberately split by Owner:

| Mechanism | Owner and effect | Not truth for |
| --- | --- | --- |
| Prepare identity resolution | Server extracts `workerProperties.clientWorkerKey` and maps it with `workerGroupId` to a long-lived `workerId` | authentication or Worker scheduling state |
| Prepare Endpoint Binding | Server persists `workerId -> endpointManagerId` and projects the complete Properties through Kernel Worker upsert | connection liveness or credentials |
| Connection identity Report | Worker sends `worker.connection.identify` with `src=WORKER/sourceId=workerId` and exact `null` payload | persistent Endpoint Binding, WorkerGroup membership, or authentication |
| Route Verification | Server read-only compares the workerId's persisted Binding with the receiving endpoint; Adapter caches the first successful workerId route until process close/restart | authentication, online truth, or persistent Binding |
| Connection Activation | Adapter installs its process-local current Channel | Worker resource, online, or attribute truth |
| Assignment / Result Fence | Kernel validates lease and opaque Result Routing evidence | connection state |

```text
Task Dispatch
  -> DeliveryCommand in endpoint-manager mailbox

Worker Delivery
  -> point HTTP or active Adapter transport
  -> Worker execution
  -> DeliveryReport ingress

Result Routing
  -> decode owner context
  -> TaskItem/Worker truth convergence
```

## Message Contracts

The Kernel defines two asymmetric transport-neutral DTOs:

```text
DeliveryCommand(
  src: DeliveryEndpoint,
  dst: DeliveryEndpoint,
  messageType: String,
  executeBeforeMillis: positive millis,
  payload: String,
  forward: String
)

DeliveryReport(
  src: DeliveryEndpoint,
  sourceId: nonblank opaque String,
  dst: DeliveryEndpoint,
  messageType: String,
  outcomeCode: nonblank String,
  payload: String,
  forward: String
)
```

Current production Task execution uses:

```text
command.src = TASK
command.dst = WORKER
command.messageType = TaskItem.eventCode
command.payload = deterministic TaskItem payload JSON
command.forward = encoded ResultContext

result.dst = TASK
result.src = WORKER
result.sourceId = workerId
result.messageType = command.messageType
result.forward = command.forward
```

`DeliveryEndpoint.KERNEL` is the result-owner coordinate for internal Kernel
evidence. Its first production consumer is Worker Serviceability:

```text
Command
  src=KERNEL, dst=ADAPTER
  messageType=platform.adapter.worker-connections.snapshot

expected Report
  src=ADAPTER, dst=KERNEL
  same messageType and opaque worker-serviceability forward

or Adapter-produced Route evidence
  src=ADAPTER, dst=KERNEL
  messageType=platform.adapter.worker-connection.changed
  forward=worker-serviceability-evidence:v1

or Adapter-produced delivery-expiry evidence
  src=ADAPTER, dst=KERNEL
  messageType=platform.adapter.worker-delivery.expired
  forward=worker-serviceability-evidence:v1
```

The optional Kernel Dispatch Pacer writes Adapter-scoped probe request HASH
fields. Server constructs the Command only after higher-priority sources leave
response capacity, Adapter executes its existing connection-snapshot Handler,
and Server appends the `ADAPTER -> KERNEL` Report to the Kernel result handoff.
The Adapter also emits one-Worker evidence for exact verified Route
connected/disconnected transitions through the same Result Process and Server
append boundary. An expired TASK-to-Worker Command atomically offers its normal
23002 TASK Report and a separate one-Worker KERNEL evidence Report to that same
Process. `KERNEL` does not authorize Server or Transport to call a score owner.

Delivery defines no outer message or correlation ID.
`DeliveryReport.fromCommand()` routes the Report to the Command source and
copies only `messageType` plus opaque `forward`. TaskItem `messageId` remains
owned by the Task contract and, for Task delivery, is contained in the encoded
ResultContext without becoming visible to Delivery owners.

The DTO contract validates structure rather than current route combinations.
Receiving Owners enforce direction and source consistency. A Report's
`src + sourceId` names its producer (`WORKER + workerId` or
`ADAPTER + adapterId`); it is not an authentication credential.

`forward` is copied unchanged through Server, Adapter, and Worker. Result
Routing is the first owner allowed to decode it. For Task work it contains the
Task, Item, Worker, WorkerGroup, and Worker lease coordinates required by the
existing result fences.

`executeBeforeMillis` means the Worker must not start after the deadline. Once
execution starts, it does not stop or re-check the deadline.

There is no nested assignment handoff DTO and no generic connection-message
envelope.

Long-lived connection control uses those same DTOs:

```text
Worker -> Adapter
  DeliveryReport(src=WORKER, sourceId=<workerId>, dst=ADAPTER,
               messageType=worker.connection.identify,
               outcomeCode=200,
               payload="null", forward="")

Adapter -> Worker
  DeliveryCommand(src=ADAPTER, dst=WORKER,
                messageType=worker.connection.close,
                payload="null", forward="")
```

The close Command is a first-version terminal instruction and carries no
reason, sleep duration, or retry hint.

DIRECT_CALL reuses the same transport DTOs and Worker Command mailbox, but not
Task scheduling or Result Routing:

```text
one adapterId-scoped public Direct Call
  -> optional same-Group workerId -> opaquePayload map
  -> only Workers bound to that Adapter are admitted
  -> Worker: HSETNX offer to the shared Worker Command Hash
  -> Adapter: Server instance-local bounded FIFO
  -> unified Adapter Command batch
  -> SYSTEM -> WORKER, or SYSTEM -> ADAPTER under an opaque response key
  -> unified Adapter Report batch
  -> Server instance-local waiter
```

`workerGroupId` is a request-body resource coordinate, not the public route
owner. Worker mode pairs it with a `1..100` entry `workerPayloads` map and
creates one Command with its own payload per admitted Worker. Adapter mode
instead carries one top-level `opaquePayload`; the two shapes are exclusive.
One call never fans out across Adapters.

Worker Direct Commands use the delivery owner's non-overwriting offer. An
empty Worker field becomes `OFFERED`; any occupied field is `OCCUPIED` and the
target is rejected immediately. Authoritative TASK append retains its existing
replace behavior, so it may replace an offered Direct Command until Adapter
consumption. After compare-delete consume, no later append recalls or preempts
the consumed Command; Direct and TASK handlers may both execute serially.

The Adapter FIFO and waiter correlation are Server-instance memory. Worker
Commands use the existing Redis Hash, while Adapter queues remain process
local. Expiry produces no synthetic Direct Result; late or missing evidence
becomes `unobserved` at the Server waiter. Server treats event code and payload
as opaque execution data rather than maintaining a whitelist. Adapter events
come from one immutable composition-time
`platform.adapter.*` Handler map; Worker events come from the immutable
`platform.worker.*` plus `extension.worker.*` Definition map. Unknown events
return observed Adapter `23005` or Worker `3302` results. This path does not
create a Task, claim an Item or Worker lease,
or write Result Routing truth. Authorization belongs before this use case in a
future API Session owner.

`DIRECT_CALL` names caller-selected addressing, not a persisted Worker mode, a
third transport lane, a Kernel score band, or a safety fence. Server neither
reads nor changes Worker score for admission. Any stronger pause, drain,
exclusion, preemption or migration transaction requires a separate Fleet
coordination owner and is not implied here.

## Task Dispatch

After exact Item and Worker claims, `TaskItemDispatcher` constructs one
`DeliveryCommand` and returns:

```text
endpointManagerId -> workerId -> DeliveryCommand
```

`endpointManagerId` is the assignment-time delivery route snapshot. `workerId`
is the mailbox field and active-channel route. Neither is duplicated inside
the command.

`TaskDispatchPacer` appends one Map per endpoint-manager bucket. A Worker lease
prevents a second valid concurrent assignment. If a mailbox field still
exists, it is stale transport residue; append first uses `HSETNX`, then replaces
the occupied residue. The new lease-backed command is authoritative, including
over a pending Direct Command.

## Redis Mailbox

```text
xa_mass:<scope>:delivery:commands:<endpointManagerId>
  HASH workerId -> DeliveryCommand JSON
```

The owner exposes two distinct writes:

```text
append_worker_commands
  HSETNX then replace occupied fields
  -> APPENDED | REPLACED

offer_worker_commands
  one same-key Lua batch of HSETNX operations
  -> OFFERED | OCCUPIED per worker
```

`offer_worker_commands` is a generic mailbox operation, not a DIRECT_CALL or
scheduling contract. It validates a caller-bounded, single-Adapter map and
never changes an occupied field.

Point consume atomically performs `HGET + HDEL` for one workerId.

Batch consume performs:

```text
HRANDFIELD key limit WITHVALUES
-> exact compare-and-delete observed workerId/value pairs
-> decode and deadline filter
```

The batch contract has no cursor. It is bounded acquisition, not ordered
iteration. It provides no FIFO, priority, stable order, or global fairness.
Exact delete preserves a concurrently replaced value. Point and batch access
to the same workerId are competing consumers, so at most one obtains the
command.

Expired or corrupt values are removed and not returned. Cross-worker and
cross-endpoint operations are not atomic.

## Server Worker Delivery API

The Java Server owns the Worker Delivery HTTP access boundary.

Point Worker access:

```text
POST /api/v1/worker-delivery/endpoint-managers/{id}/workers/{workerId}/commands:poll
POST /api/v1/worker-delivery/endpoint-managers/{id}/workers/{workerId}/results
```

The Worker first completes one Server Prepare control call outside this
delivery API. Each point request verifies that the persisted Worker Binding is
`system-polling`; this is a route-consistency check rather than authentication.
Point poll returns `204` or the direct `DeliveryCommand` fields. Point result
accepts a direct `DeliveryReport` and requires `src=WORKER`, `sourceId` equal
to the path workerId, `dst=TASK`, and `200` or Worker-owned `3...`.
This is the pure polling Worker path.

Active Adapter batch access:

```text
POST /api/v1/worker-delivery/endpoint-managers/{id}/commands:consume
body: {"limit":100}

POST /api/v1/worker-delivery/endpoint-managers/{id}/results:append
body: {"results":["<encoded DeliveryReport>", "..."]}
```

Batch consume returns:

```json
{
  "commands": {
    "worker-1": {
      "src": "TASK",
      "dst": "WORKER",
      "messageType": "extension.worker.telecom.phone.inspect",
      "executeBeforeMillis": 1234567890,
      "payload": "{\"phoneNumber\":\"+14155552671\"}",
      "forward": "..."
    }
  }
}
```

For each consume request, Server first consumes the Adapter Direct FIFO up to
the request limit. With the remaining capacity it calls
`WorkerCommandRuntime.consumeWorkerCommands` exactly once. That shared Hash may
contain TASK or SYSTEM Commands for different Worker fields; there is no
authority-specific Worker queue. If capacity still remains, Server may consume
up to 100 Adapter-partitioned Serviceability request fields and add one
`KERNEL -> ADAPTER` connection-snapshot Command. Worker entry keys are workerId
addresses. Adapter and Serviceability entry keys are response-local, opaque,
and ignored by Adapter dispatch, which relies on `dst`.

The Adapter result endpoint accepts a mixed encoded batch and selects its owner
by `dst`. TASK is validated and appended through the Task Result owner; SYSTEM
is handed to the Direct Call owner; KERNEL accepts only path-consistent Adapter
reports and appends them to `WorkerServiceabilityRuntime`. Each selected owner
performs its own forward and payload validation. The response reports combined
accepted and rejected counts. Remote unavailability keeps the Adapter's one
pending batch for retry.

Priority is limited to the Adapter Direct FIFO prefix. It does not reorder
commands already present in the Adapter's local FIFO, preempt an in-flight
Worker Handler, or reserve delivery capacity. A full Adapter queue delays the
next consume call. Because Direct waiter state is Server-instance memory,
Adapter consume and result requests must return to the Server process that
accepted the call; no distributed waiter correlation exists.

`system-polling` is a logical endpoint-manager binding for pure polling
Workers. It may use only point access, never batch access.

## Long-Lived Adapters

An active Adapter consumes one endpoint-manager mailbox and maintains current
transport routes:

```text
workerId -> current route state
```

WebSocket and line Socket first receive a strict identity `DeliveryReport`:

```json
{
  "dst":"ADAPTER",
  "forward":"",
  "messageType":"worker.connection.identify",
  "outcomeCode":"200",
  "payload":"null",
  "sourceId":"server-issued-worker-id",
  "src":"WORKER"
}
```

Every physical connection sends identity first. When one Adapter process sees a
workerId for the first time, exactly one Channel becomes its pending verification
owner and asks Server through
`/endpoint-managers/{adapterId}/workers/{workerId}:verify-binding` whether its
persisted Worker Binding points to this Adapter. Another initial Channel for the
same workerId is physically closed. Only successful route verification installs
the active Channel; no ACK is sent. WorkerGroup is absent from the identity,
Channel metadata, and Adapter route state.
Adapter does not invoke Kernel Worker upsert. A definite 4xx rejection emits
`ADAPTER/worker.connection.close` and closes the physical connection after
flush. Remote API unavailability or 5xx only closes the physical connection, so
the Worker Client may reconnect to the same Endpoint.

Ordinary disconnect removes only the exact active Channel. The verified route
remains in that Adapter process, so the next identity for the same workerId
skips Server verification and replaces the current Channel. WorkerGroup does not
participate in route admission or availability evidence. The retained
disconnected route is not Endpoint Binding, authentication, authorization,
Worker liveness, or Worker score truth. It has no periodic recheck; its
verification evidence is bounded by Adapter-local retention and disconnected
cache capacity, is cleared on Adapter close/restart, and currently has no
system unbind operation.

After route verification and connection activation they exchange direct
protocol JSON:

```text
Adapter -> Worker : DeliveryCommand
Worker  -> Adapter: DeliveryReport
```

The Adapter forwards Worker-targeted Task/System commands unchanged.
Adapter-targeted controls are dispatched through a finite immutable local map;
the defaults are probe, bounded current-connection observation, and exact
current-connection close. They execute without entering the network Server.
Connection observation is Adapter-local truth only: active verified Channel is
not schedulability, Binding, writability or Worker idleness. Exact close
preserves the verified route cache and returns physical close to the selected
Server, allowing the existing Worker Client reconnect path to establish a new
Channel. The selected physical Server normalizes inbound text to `String`, then invokes one sharable Netty
callback Handler. That Handler only forwards text, inactive, and failure
callbacks to the common connection mechanism; it owns no connection semantics
or state. The mechanism validates the first Report, coordinates optional first
verification, and derives each inbound Channel's phase from the Adapter
instance's route Registry. The callback Handler remains installed; there is no
phase enum, Session, or Pipeline replacement. Different Adapter instances
share no verified cache, route Registry, or Channel state. The fixed identity Report is handled directly;
there is no Adapter event registry or plugin dispatcher. Reports whose
`dst=ADAPTER` never enter Server, Redis, or Kernel Result Routing. Unknown
Worker-originated local events on an established connection are logged and
dropped; Adapter-targeted DIRECT_CALL commands receive `23005` from the local
event executor.

Before identity, malformed or non-identity input closes the physical Channel.
During asynchronous route verification reads remain enabled, but later input is
released and dropped without buffering or closing that Channel. Once bound,
malformed JSON, repeated identity, unknown Adapter events, mismatched
`src/sourceId`, unsupported destinations, and Worker-originated Adapter `2...`
outcomes are logged and dropped while the Channel remains usable.

The Adapter keeps consumed commands in a bounded local queue. A command with
no active connection rotates until the Worker reconnects or the command
expires. On expiry the Adapter may create
`WorkerDeliveryAdapterErrorCode.COMMAND_EXPIRED` using the original message
type and opaque forward context. If a send has started and later fails, delivery is
`UNKNOWN`; the Adapter must not fabricate rejection evidence.

Bound Worker Reports declaring the bound workerId and using `200` or
Worker-owned `3...` enter the single Result queue for `dst=TASK` or
`dst=SYSTEM`, preserving their original encoded JSON. Adapter-produced KERNEL
snapshot and Route-change Reports enter that same queue. A full or closed queue still closes the
Channel for TASK backpressure; best-effort SYSTEM evidence is dropped without
closing it; best-effort KERNEL evidence is also dropped without closing a
Worker Channel. Adapter-generated TASK `COMMAND_EXPIRED` enters
the same queue. DIRECT_CALL expiry creates no synthetic evidence because the
Server waiter owns timeout. There is no command/result coupling, ACK, durable
Adapter queue, or exactly-once promise.

One finite construction factory returns only the public Adapter contract. It
instantiates one package-private Adapter scheduling mechanism per endpoint and
selects one complete WebSocket or line-Socket physical Server. Every instance
independently owns three layers: the Adapter aggregate owns lifecycle,
network shutdown sequencing, and an `AdapterProcessManager`. The Manager owns
the finite scheduled Process list, its one same-lifetime scheduler, phase-local
quiescence, round isolation, and reverse finish; the Command and Report
Processes each own one private finite queue and one owner-local Remote API.
Source priority is decided by Server before the Command response is created.
The Command Remote owns the unified command path and wire
decoding, the Report Remote owns result paths and wire validation, and the
route Remote owns verification status classification. A concrete
Adapter-private HTTP client is shared only by those three Remote APIs and owns
connection resources plus raw request mechanics; it does not form another
delivery owner and is never passed to a Process or connection mechanism.
One callback Handler adapts Netty events without becoming another owner. One
common connection mechanism and pure Registry own identity, verification,
route selection, and Result ingress; the concrete physical Server owns its listener, EventLoop,
every child Channel, complete Pipeline, framing, writes, asynchronous write
failure, and protocol close mapping. Physical Servers own no HTTP client, queue,
route, or verification state, while the common mechanism never writes, closes,
or mutates a Channel Pipeline directly.
Closing an Adapter therefore closes all physical child Channels, not only
verified routes. The two physical Servers do not form a public SPI or a
transport-kind branch.

Physical disconnect is a reconnectable network fact. Only
`ADAPTER/worker.connection.close` instructs the Worker to end its current run.

## Worker

The Worker receives `DeliveryCommand` and:

```text
deadline check before start
-> use the full messageType Event Name
-> parse payload into Handler parameters
-> execute selected WorkerEventDefinition
-> return WorkerCommandOutcome(outcomeCode, payload)
-> Transport constructs DeliveryReport.fromCommand(WORKER, workerId, ...)
```

Handler completion maps to `200`; input errors map to `3301`; unknown events
map to `3302`; Handler failures map to `3303`, and invalid output maps to
`3304`. The Handler returns an
already serialized opaque String result. `"null"` represents no business
value.

SYSTEM and TASK Commands use this same Event Name Dispatcher and the same
per-connection Client callback lane. Command `src` is source evidence, not a
Handler lookup coordinate. They cannot
preempt an already running Handler. All synchronous handlers must therefore be
bounded and thread-safe; a long-running management workflow needs another
owner rather than a transport Handler. Java and Android assemblies include default
`platform.worker.probe`, live `platform.worker.properties.snapshot`, and static
`platform.worker.events.snapshot` Definitions before Host extensions. The
properties result excludes the assembly-owned `clientWorkerKey`; arbitrary
Host `extension.worker.*` Definitions remain equally callable. The repository
[Transport Platform Event Catalog](../../../transport/EVENTS.md) is a human
projection, not Handler-map or WorkerGroup truth.

Polling submits the direct result through the point API. WebSocket and Socket
send direct result JSON to the Adapter. A Worker that receives an already
expired command drops it silently.

The long-connection Transport consumes a non-expired
`ADAPTER/worker.connection.close` directly and ends the current run without a
DeliveryReport. The control Command never enters the business Definition
registry. A wrong source or expired close Command does not terminate the run.
Identity send failure is a physical connection send failure and is handled by
the Client's existing reconnect budget.

## Result Routing

DeliveryReport Redis keys are documented in
[Task Result Runtime Redis Shape](../runtime-redis/task-result-runtime-redis-shape.md).

Server validates producer-owned endpoint codes and appends accepted Task
reports to an explicit `TaskResultRuntime` lane:

```text
Worker 200                    -> SUCCESS
Worker-owned 3...             -> FAILURE
Adapter-owned Task rejection  -> FAILURE
```

The Result Convergence coordinator consumes the `SUCCESS` and `FAILURE` keys as
separate fixed lanes. `TaskResultBatchPolicy` requires `dst=TASK`, decodes
`forward` as ResultContext and never reads `outcomeCode`. SUCCESS stores and
promotes the Item before using the completed-HOT exact release; FAILURE only
releases the assignment lease. FAILURE may therefore execute several Batches
out of completion order under the shared Result capacity; SUCCESS remains
single-flight. Adapter connection and delivery-expiry evidence remains on the
separate KERNEL result key and runs as the optional single-flight third lane.

The Report `messageType` is not used as truth or a fence. Task and Worker owner
coordinates from `forward`, together with exact score/lease operations, decide
whether late or duplicate evidence can change state. Delivery has no outer
message ID; future tracing or generic correlation requires a separate explicit
contract.

## Failure Boundary

- Mailbox consume is destructive; Adapter/Server process failure can lose a
  command before Worker receipt.
- Worker sends each Result once; a failed send is lost. Adapter queues are
  process-local and can be lost.
- Adapter batch retry can duplicate a DeliveryReport at Server ingress.
- A mixed TASK/SYSTEM/KERNEL pending batch is retried as one unit; a DIRECT_CALL
  waiter may already have timed out when its late evidence reaches Server.
- Instance-local Adapter Direct FIFO and waiter state require Adapter HTTP
  affinity to the Server process that accepted the call.
- Result Routing fences converge late and duplicate evidence.
- Item claim and Worker lease expiry recover missing evidence.

These are deliberate best-effort, at-least-once boundaries. Transport ACK,
pending/ack Redis, Worker connectivity truth, Adapter ownership HA, and
exactly-once execution are outside this mechanism.
