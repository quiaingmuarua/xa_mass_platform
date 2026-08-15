# Worker Delivery Dispatch

Status: active mechanism contract.

Worker Delivery carries already-assigned work. It does not select Workers,
claim TaskItems, mutate scores, or decide result truth.

The control and connection vocabulary is deliberately split by Owner:

| Mechanism | Owner and effect | Not truth for |
| --- | --- | --- |
| Identity Register | Server extracts `workerProperties.clientWorkerKey` and maps it with `workerGroupId` to a long-lived `workerId` | authentication or Worker scheduling state |
| Endpoint Binding | Server persists `workerId -> endpointManagerId` and projects it through Kernel Worker upsert | connection liveness or credentials |
| Connection identity Report | Worker sends `worker.connection.identify` with `src=WORKER` and `sourceId=workerId` to declare every physical Channel; the Adapter may initiate first-seen route verification | persistent Endpoint Binding or authentication |
| Route Verification | Server read-only compares the persisted route with the receiving endpoint; Adapter caches first success for that workerId until process close/restart | authentication, online truth, or persistent Binding |
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
               outcomeCode=200, payload="null", forward="")

Adapter -> Worker
  DeliveryCommand(src=ADAPTER, dst=WORKER,
                messageType=worker.connection.close,
                payload="null", forward="")
```

The close Command is a first-version terminal instruction and carries no
reason, sleep duration, or retry hint.

Direct CONTROL_ONLY management reuses the same transport DTOs but not the Task
mailbox or Result Routing:

```text
Server instance-local bounded mailbox
  -> Server-selected unified Adapter Command batch
  -> SYSTEM -> WORKER, or SYSTEM -> ADAPTER at @adapter
  -> unified Adapter Report batch
  -> Server waiter
```

The Server admits Worker controls only from its current pause-score read, but
that observation is not an execution lock and Adapter never reads score.
Mailbox slots, Adapter queues, and waiter correlation are memory-only and may
be lost on process failure. CONTROL_ONLY expiry produces no synthetic Result;
late or missing evidence becomes `unobserved` at the Server waiter. The fixed
Adapter-local surface contains only `adapter.probe`; Worker management events
come from statically assembled `src=SYSTEM` Worker Definitions. This path does
not create a Task, claim an Item or Worker lease, or write Result Routing truth.

`CONTROL_ONLY` is not a persisted Worker mode, a third transport lane, or a
Kernel score band. Server derives the classification from the pause coordinate
only while admitting a call. Pause does not revoke an existing Worker lease,
drain a TASK Command already acquired by an Adapter, or interrupt a Handler;
resume does not wait for the Control Call. Any stronger
`pause -> drain -> control -> resume` transaction would require a separate
management owner and is not implied here.

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
the occupied residue. The new lease-backed command is authoritative.

## Redis Mailbox

```text
wd:{prefix}:endpoint-manager:{endpointManagerId}:worker-commands
  HASH workerId -> DeliveryCommand JSON
```

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

The Worker first completes Server Register and Bind control calls outside this
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
      "messageType": "telecom.phone.inspect",
      "executeBeforeMillis": 1234567890,
      "payload": "{\"phoneNumber\":\"+14155552671\"}",
      "forward": "..."
    }
  }
}
```

For each consume request, Server selects one source only. A non-empty
CONTROL_ONLY mailbox has strict priority; otherwise Server consumes the TASK
mailbox. It never merges the two maps. The Adapter result endpoint accepts a
mixed encoded batch: `dst=TASK` is validated and appended through the Kernel
Result owner, while `dst=SYSTEM` is correlated with the Server-local Control
waiter. The response reports combined accepted and rejected counts. Remote
unavailability keeps the Adapter's one pending batch for retry.

Strict priority is limited to this remote source-selection decision. It does
not reorder commands already present in the Adapter's single FIFO, preempt an
in-flight Worker Handler, or reserve delivery capacity. A full Adapter queue
delays the next consume call, and sustained CONTROL_ONLY traffic may delay TASK
mailbox acquisition. Because the Control mailbox is Server-instance memory,
the Adapter's consume and result requests must return to the same Server
instance; no distributed mailbox or cross-instance waiter correlation exists.

`system-polling` is a logical endpoint-manager binding for pure polling
Workers. It may use only point access, never batch access.

## Long-Lived Adapters

An active Adapter consumes one endpoint-manager mailbox and maintains current
transport routes:

```text
workerId -> current connection
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
workerId for the first time, exactly one Channel becomes its pending
verification owner and asks Server whether the persisted Worker Binding points
to this Adapter's endpoint-manager ID. Another initial Channel for the same
workerId is physically closed. Only successful route verification records the
process-local verified workerId and installs the active Channel; no ACK is sent.
Adapter does not invoke Kernel Worker upsert. A definite 4xx rejection emits
`ADAPTER/worker.connection.close` and closes the physical connection after
flush. Remote API unavailability or 5xx only closes the physical connection, so
the Worker Client may reconnect to the same Endpoint.

Ordinary disconnect removes only the exact active Channel. The verified route
remains in that Adapter process, so the next identity for the same workerId
skips Server verification and replaces the current Channel. The verified set is
not Endpoint Binding, authentication, authorization, Worker liveness, or Worker
score truth. It has no TTL or periodic recheck, is cleared on Adapter
close/restart, and currently has no system unbind operation.

After route verification and connection activation they exchange direct
protocol JSON:

```text
Adapter -> Worker : DeliveryCommand
Worker  -> Adapter: DeliveryReport
```

The Adapter forwards Worker-targeted Task/System commands unchanged. The fixed
Adapter-local `adapter.probe` control is executed without entering the network
Server. The selected physical Server normalizes inbound text to `String`, then invokes one sharable Netty
callback Handler. That Handler only forwards text, inactive, and failure
callbacks to the common connection mechanism; it owns no connection semantics
or state. The mechanism validates the first Report, coordinates optional first
verification, and derives each inbound Channel's phase from the Adapter
instance's route Registry. The callback Handler remains installed; there is no
phase enum, Session, or Pipeline replacement. Different Adapter instances
share no verified cache, route Registry, or Channel state. The fixed identity Report is handled directly;
there is no Adapter event registry or plugin dispatcher. Reports whose
`dst=ADAPTER` never enter Server, Redis, or Kernel Result Routing. Unknown
local events on an established connection are logged and dropped.

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
`dst=SYSTEM`, preserving their original encoded JSON. A full or closed queue
still closes the Channel for TASK backpressure; best-effort SYSTEM evidence is
dropped without closing it. Adapter-generated TASK `COMMAND_EXPIRED` enters
the same queue. CONTROL_ONLY expiry creates no synthetic evidence because the
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
-> use messageType as eventCode
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

`src=SYSTEM` management Definitions use this same synchronous Dispatcher and
the same per-connection Client callback lane as TASK Definitions. They cannot
preempt an already running Handler. The current CONTROL_ONLY policy therefore
requires them to be fast, bounded, thread-safe, and non-blocking; a network,
disk, or long-running management workflow needs another owner rather than a
transport Handler.

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
[Worker Result Runtime Redis Shape](../runtime-redis/worker-result-runtime-redis-shape.md).

`WorkerResultRuntime` partitions transient evidence only by outcome class.
`ResultRoutingPacer` consumes one class, requires `dst=TASK`, decodes `forward`
as ResultContext, and delegates:

```text
200                          -> success disposition policy
other nonblank 3...          -> Worker failure disposition policy
other nonblank outcomeCode   -> Adapter rejection disposition policy
```

This classification is coarse evidence only. Exact retry or band policy must
come from explicit typed interface fields rather than parsing an outcome
subcode.

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
- A mixed TASK/SYSTEM pending batch is retried as one unit; a CONTROL_ONLY
  waiter may already have timed out when its late evidence reaches Server.
- Instance-local Control mailbox and waiter state require Adapter HTTP affinity
  to the Server process that accepted the call.
- Result Routing fences converge late and duplicate evidence.
- Item claim and Worker lease expiry recover missing evidence.

These are deliberate best-effort, at-least-once boundaries. Transport ACK,
pending/ack Redis, Worker connectivity truth, Adapter ownership HA, and
exactly-once execution are outside this mechanism.
