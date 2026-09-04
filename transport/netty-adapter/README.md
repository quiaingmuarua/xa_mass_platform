# XA Mass Netty Worker Delivery Adapter

Status: Java 21 multi-endpoint Adapter with explicit aggregate, Batch Dispatcher,
connection, and complete WebSocket / line-Socket physical Server owners.

The production owner cut is frozen. Hardening may refine an owner's local
behavior and proof, but must not introduce a dynamic lifecycle registry,
shared Server base, Session, Bridge, or protocol-extension framework.

This module is a plain `java-library`. It does not depend on Spring, Server,
Kernel, Redis, score, or Pacer code. It reaches the Server Worker Delivery
Command and Report batch APIs through one `WorkerDeliveryRemoteApi`. First
route verification uses the injected `WorkerRouteVerifier` application port.
All Adapter instances share one process-lifetime JDK `HttpClient` for physical
HTTP resources.

## Instance Boundary

Server creates one process-scoped `NettyWorkerDeliveryAdapterFactory` from
the Remote API base URI, request timeout, and one `WorkerRouteVerifier`. The
Factory owns one immutable `WorkerDeliveryRemoteApi` and one codec, and creates
the two finite physical protocol variants from a complete
`NettyWorkerDeliveryAdapterConfig`. It
returns only the public `WorkerDeliveryAdapter` contract. Both variants
instantiate the same
package-private `NettyWorkerDeliveryAdapter`; every instance independently
owns:

```text
adapterId = endpointManagerId
listenHost + listenPort
one `WorkerConnectionMechanism`, `WorkerRouteRegistry`, and properties cache
one sharable `WorkerConnectionInboundHandler` adapting Netty callbacks
one complete `WebSocketNettyWorkerServer` or `SocketNettyWorkerServer`, with
one acceptor EventLoop and a CPU-bounded child EventLoop group
one fixed `AdapterProcessManager` with Command and Report Dispatchers
one retry-only Command Queue and three destination-specific Report Queues
one pure `DeliveryCommandProcess` and one `DeliveryReportDispatcher`
two resident daemon platform threads, one owned by each Dispatcher
```

The common Adapter aggregate owns public lifecycle and network ordering. Its
fixed `AdapterProcessManager` owns the two Dispatcher lifecycles and one shared
join deadline; it is not a Process registry. The Command
`BatchDispatcher<DeliveryCommandItem>` owns one finite retry Queue, its current
batch, retry placement, interruptible backoff, stop intent, and named daemon
platform thread. It also calls its fixed fresh supplier once per outer
iteration. `DeliveryCommandProcess` processes one supplied batch once and owns
no Queue, loop, sleep, thread, pending batch, or lifecycle state. The
`DeliveryReportDispatcher` owns three finite destination Queues, one rotating
consumer, destination-specific failure policy, and the second named daemon
platform thread. Queue count does not determine thread count.
The stateless inbound Handler only forwards normalized text, inactive, and
failure callbacks.
The shared connection mechanism owns identity
interpretation, route verification, Command routing, and Result ingress; its
Registry alone owns
the single per-Worker route truth. Each claimed Channel carries only its Worker
identity as Adapter-local Netty metadata for inbound correlation. The selected
physical Server owns its listener, acceptor EventLoop, child EventLoop group,
all child Channels, complete Pipeline, framing, physical writes, asynchronous
write failures, and close behavior. These are internal owners, not a public SPI
or transport-kind branch.

The supported construction surface is deliberately limited to
`WorkerDeliveryAdapter`, `WorkerDeliveryAdapterManager`,
`WorkerRouteVerifier`, `NettyWorkerDeliveryAdapterConfig`, and
`NettyWorkerDeliveryAdapterFactory`. The config is one flat, complete Adapter
construction value; the Factory destructures it and passes each internal owner
only its own primitive values. Java types
under `netty.internal` are `public` only where repository packages must
collaborate without JPMS; they are repository-internal and carry no external
compatibility promise.

Implementation packages follow owner responsibility rather than protocol
similarity:

```text
netty/
  one complete public config + process factory + package-private aggregate
netty/internal/process/
  fixed Process Manager + Command Batch Dispatcher + Report Dispatcher
  + one pure Command Processor
netty/internal/connection/
  one Netty callback adapter + shared connection semantics + pure route truth
netty/internal/remote/
  one Factory-owned Remote API facade + one process-shared JDK HTTP client
netty/internal/network/
  internal Server contract + complete WebSocket and Socket physical owners
```

Java collaborators crossing those owner packages are `public` only for module
assembly and remain under `netty.internal`; they are not supported construction
contracts, and Server is guarded from importing them.
`WorkerConnectionInboundHandler` has only one dependency: the connection
mechanism. It owns no codec, route, verification, Result, or physical network
behavior. The shared connection mechanism exposes a concrete `deliver(...)`
owner operation and depends only on `WorkerRouteVerifier` plus the Report
`DeliveryReportDispatcher`. It sees normalized strings and Netty Channels as route
addresses, but all physical write/close operations return through
`NettyWorkerServer`; it does not see WebSocket
frames, Socket lines, handshake types, listener resources, or Pipeline
mutation.

`WorkerDeliveryRemoteApi` owns the two fixed Command consume and Report append
methods, including their paths, wire JSON, expected HTTP statuses, and
method-specific failure classification. One instance is
created per process Factory and keeps only its immutable base URI, request
timeout, and codec; every Adapter created by that Factory uses the same facade.
Its process-level static JDK `HttpClient` keeps shared connection resources,
uses HTTP/1.1, carries no Adapter configuration, and has no Adapter lifecycle.
Its explicit process-level virtual-thread executor owns HTTP execution without
restoring the JDK client's default cached platform-thread pool. Each request
receives the Factory-owned facade's timeout. Processes and connection mechanism
never see the Client, URL, status, or HTTP JSON contract.

WebSocket and Socket keep complete, separately understandable physical Server
implementations. Parameterized tests constrain their common physical contract
and only three cross-layer Adapter paths: Command/Result round trip, verified
reconnect, and rejection flush-before-close. Production does not share a
lifecycle helper or base class.

`WorkerDeliveryAdapterManager` manages complete instances: register before
start, start in order, and close in reverse order. It is a lifecycle owner, not
an Adapter lookup or observation directory. Multiple instances in one JVM are
meaningful only when they use different listener endpoints and different
endpoint-manager mailboxes.

Do not run competing Adapter instances for the same `endpointManagerId`.
Each physical Server keeps accept independent from established-connection I/O.
It owns one acceptor thread, an accept backlog of `4096`, and
`max(4, availableProcessors * 2)` child EventLoop threads. Netty preserves one
Channel's callback order while allowing different Worker Channels to progress
independently. The CPU-derived I/O width and listen backlog are internal
physical-Server policy, not Worker execution threads or per-Worker resources.

## Connection Protocol

WebSocket uses:

```text
/api/v1/worker-delivery/websocket
```

Socket uses one compact JSON value per UTF-8 line. Both transports start
without an active Worker route. The first inbound value must be this strict
Adapter-directed identity Report:

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

Every physical connection sends this identity first. Adapter requires
`src=WORKER`, a non-blank opaque `sourceId`, and exact `null` payload. It does
not parse the workerId format.

Each Adapter process submits a single route-verification request when it has no
current route or retained verification evidence. The first Channel atomically
claims the single pending Route entry; another initial Channel for the same
workerId is physically closed. Server confirms that the workerId's current Endpoint Binding
points to this Adapter's `endpointManagerId`. Successful verification
atomically turns that same entry into `workerId -> current Channel` without an
identity ACK. No WorkerGroup metadata is stored on the Channel or route. A
`WorkerRouteVerifier.REJECTED` decision causes the Adapter to write
`DeliveryCommand(ADAPTER -> WORKER, worker.connection.close, payload="null")`
and close the physical Channel after the write flushes. An exceptional
verification completion only closes the physical Channel, allowing the Worker
Client to consume its current-Endpoint reconnect budget.

An unverified Channel is never visible to `DeliveryCommandProcess`. Reads stay
enabled during asynchronous verification, but every later frame is released and
dropped until verification completes; there is no pre-verification message
buffer. If that Channel disconnects, its exact pending entry is cancelled and a
later connection may start verification again. A late callback cannot activate
the cancelled Channel.

Ordinary disconnect removes only the exact active Channel. Fresh verification
evidence remains cached, so a later identity for the same workerId can skip
Server verification. WorkerGroup does not participate in route admission, route
state, or availability evidence. The retained `Disconnected` route is not
persistent Endpoint Binding, authentication, authorization, Worker online
truth, or a Property cache. It is bounded by the configured retention and
capacity and is always cleared when the Adapter closes or restarts. There is
currently no unbind operation.

After connection activation:

```text
Adapter -> Worker : direct DeliveryCommand JSON
Worker  -> Adapter: direct DeliveryReport JSON
```

There is no outer frame DTO. Adapter identity comes from its listener and
mailbox configuration, not from a URL path or message field.

After the physical Pipeline normalizes input to `String`, one sharable
`WorkerConnectionInboundHandler` forwards Netty callbacks to the instance's
`WorkerConnectionMechanism`. The Handler stores no connection-local state. The
mechanism validates the first Report and coordinates optional asynchronous
verification. Later input is accepted only when the claimed workerId has
verification evidence in `WorkerRouteRegistry`; input received while the
single Route entry is pending is dropped. The callback Handler remains installed for the full
physical connection; there is no phase enum, Session, or Pipeline replacement. The fixed
identity Report is not routed through an
event registry or plugin dispatcher. Adapter-directed Reports never enter the
Server Result queue. Repeated identity and unknown Adapter events on an
established connection are logged and dropped. Before identity, a malformed,
invalid, or non-identity Report closes the physical Channel.

Once bound, malformed JSON, repeated identity, unknown Adapter events,
mismatched `src/sourceId`, unsupported destinations, and Worker-originated
`2...` outcomes are logged and dropped without closing the Channel. A valid
Worker Report must use `src=WORKER`, the bound workerId, and outcome `200` or a
Worker-owned `3...`. `dst=TASK` and `dst=SYSTEM` enter their respective Report
Queues as decoded `DeliveryReport` objects. A full or closed TASK Queue closes
the exact Channel; best-effort SYSTEM backpressure drops the Report and keeps
the Channel usable. Adapter-produced `dst=KERNEL` evidence uses the third Queue.

Each Adapter constructs one `WorkerRouteRegistry`. It owns the process-local
`workerId -> RouteEntry` truth in one Caffeine cache. One immutable entry holds
exactly one pending Channel, one connected Channel plus verification time, or
disconnected verification evidence; these facts are never split into parallel
active/pending/verified maps. A claimed Channel stores only its workerId as
thread-safe Channel-local metadata. That attribute answers which Worker a
Channel claimed for callback correlation; it does not say whether verification
succeeded and is not a second route index or wire field.
Per-Worker `ConcurrentMap.compute` transitions prevent an old Channel's late
callback from changing a replacement route while still allowing its valid
in-flight Result before physical close.
The connection mechanism selects a route and asks its physical Server to write
a normalized command string. The WebSocket Server emits a text frame; the
Socket Server emits one UTF-8 line. Those Servers also map semantic close
reasons to a WebSocket close frame or TCP close. A verified reconnect replaces
the current Channel for that workerId. Deactivation compares exact Channel
identity, so a delayed close from an old Channel cannot remove its replacement
or verified route.
Different Adapter instances never share a Session, cache, or Channel registry.
Results already sent by an old connection are still eligible evidence; Kernel
Result Routing decides whether their `forward` context remains valid.

The mechanism emits one best-effort
`platform.adapter.worker-connection.changed` Report when an exact Route
transition first becomes connected or loses its current connected Channel.
The payload contains only `workerId`, `CONNECTED|DISCONNECTED`, and the
Adapter-observed wall-clock time; the Report is `ADAPTER -> KERNEL` with fixed
`worker-serviceability-evidence:v1` forward. First verification success and a
disconnected-cache reconnect produce `CONNECTED`. Exact inactive, write
failure, management close, and Adapter shutdown produce `DISCONNECTED`.
Connected-Channel replacement, pending verification, verification failure,
duplicate removal, and stale old-Channel callbacks produce no evidence. A full
or closed Result queue drops this evidence without closing the Worker Channel.
WorkerGroup remains outside Route state and payload.

Successful route verification is retained for ten minutes by default after a
disconnect. A reconnect inside that window activates locally without renewing
the evidence. A current connected route remains trusted for its physical
lifetime even after that time; a new Channel replaces it locally and preserves
the original verification time. Once no current Channel remains, expired
evidence is discarded and the next identity verifies remotely. Only
disconnected evidence participates in the configured capacity (`100000` by
default). Connected and pending entries have zero cache weight and no time
expiry; physical Channel resources, not the disconnected-cache budget, bound
them. Disconnected evidence is removed by TTL or capacity pressure and then
projects as `UNKNOWN`. Cache eviction never emits availability evidence.

Each Adapter also owns one `WorkerPropertiesCache` beside the Registry. It is
not route truth: it stores only the most recent successful
`platform.worker.properties.snapshot` observed from the exact current
connected Channel. A replaced old Channel may still submit valid in-flight
evidence, but it cannot refresh this projection. Properties survive ordinary
disconnect and reconnect while route verification evidence is retained. Each
entry contains the complete properties Map and an Adapter-written
`updatedAtMillis`. The time is strictly increasing for successive writes to a
retained entry, including same-millisecond updates or a short wall-clock
rollback. It is observation metadata, not a cross-restart version or CAS fence.

Properties have no independent time expiry. The connection mechanism gates
every read with current route verification evidence: losing that evidence by
TTL, route-cache capacity, or explicit clear makes the properties projection
`UNKNOWN`. A new first-verification claim also clears any previous route
lifetime's properties. No Caffeine removal callback joins the two owners;
inaccessible residue is removed lazily and remains bounded by the independent
64 MiB encoded-data budget. That budget is weighted by UTF-8 workerId plus
encoded properties bytes and may evict a connected Worker's properties without
changing its route. Management reads are quiet; only a successful current-
Channel properties result refreshes an entry.

## Delivery Processes

### Command consumption loop

The Command `BatchDispatcher` owns one private retry Queue, one fixed fresh
supplier, and its permanent consumption thread. `DeliveryCommandProcess`
receives only `DeliveryCommandItem` batches and owns delivery, expiry, Adapter
event dispatch, and Report production for that batch. It returns only the
original batch indexes whose current Route asked for `RETRY_LATER`; the
Dispatcher reconstructs those items and appends them to its Queue tail.
Server may place a bounded prefix from its Adapter Direct FIFO in a
`commands:consume` response. Remaining capacity first comes from one consume of
the shared Worker Command Hash, whose fields may contain TASK or SYSTEM
Commands. If capacity still remains, Server may add one bounded Kernel
Serviceability Adapter snapshot Command. This is remote acquisition priority,
not local preemption: a full local retry queue never postpones the next Server
consume, and an already running Worker Handler is unaffected. Sustained
higher-priority Commands may starve Serviceability acquisition by design.

The Command Dispatcher first drains at most one configured-limit retry slice;
the pure Processor applies these rules once to that batch:

```text
expired TASK
  -> independently offer the 23002 TASK Report and one KERNEL
     worker-delivery.expired Report, then remove
any other expired Command
  -> remove without synthetic evidence
no active writable Worker Channel
  -> return to the retry queue
physical Server write started
  -> remove
dst=ADAPTER
  -> ignore the entry key and dispatch through the immutable map
```

After a retry slice, the Dispatcher makes one `commands:consume` acquisition
before serving another retained slice. A non-empty fresh batch is processed
directly and does not enter or depend on retry Queue capacity. It continues
immediately,
without a fixed delay or batch-count ceiling, so sustained fresh traffic cannot
starve retained Commands. An empty response or a supplier/Processor
`RuntimeException` waits `commandBackoff` as an interruptible
local backoff. An unexpected Processor exception drops that current batch
rather than replaying Commands after a possible partial physical delivery;
normal `RETRY_LATER` still appends only the selected items to the Queue tail.
There is no inner retry loop, attempt counter, or second call to `process` for
one acquisition. The remote
endpoint remains immediate; this is not Server long polling. Adapter close
submits the Dispatcher stop intent and interrupts its thread, cancelling either the
backoff or a synchronous HTTP call. A Processor already entered may finish its
current batch, but the Dispatcher neither admits its retries nor acquires another
batch after stop.

A remote response containing more Commands than the requested limit is a
protocol failure and none of that response is dispatched.

TASK accepts `TASK -> WORKER`. DIRECT_CALL uses `SYSTEM -> WORKER` or
`SYSTEM -> ADAPTER`. Worker Serviceability uses only `KERNEL -> ADAPTER` with
`platform.adapter.worker-connections.snapshot`; every other KERNEL Adapter
event is rejected. A Worker Command entry key is its workerId; an Adapter
Command entry key is opaque and ignored. No active Channel is temporary while
the deadline remains live. DIRECT_CALL expiry creates no synthetic result;
the Server waiter owns timeout. The retry queue has no workerId index, and its
soft capacity is a backpressure target rather than delivery truth. When full,
a newly retryable batch is dropped best effort; it cannot create cross-Worker
head-of-line blocking for fresh Commands. Adapter does not read Worker score or
interpret Serviceability policy.

The composition root installs a finite immutable Adapter event map. This is an
execution surface, not a public registry or Server whitelist:

| Event | Input | Result payload |
| --- | --- | --- |
| `platform.adapter.probe` | `null` | Adapter identity and reachability |
| `platform.adapter.events.snapshot` | `null` | sorted full `eventNames` |
| `platform.adapter.worker-connections.snapshot` | `{"workerIds":[...]}` | `stateByWorkerId` |
| `platform.adapter.worker-connections.close-current` | `{"workerIds":[...]}` | `outcomeByWorkerId` |
| `platform.adapter.worker-properties.snapshot` | `{"workerIds":[...]}` | ordered `propertiesByWorkerId` containing `updatedAtMillis` and cached properties |

The Event snapshot is precomputed when the immutable map is assembled, includes
itself, and is process-local execution evidence rather than Server
configuration or routing truth. Its reserved name cannot be replaced by another
static Handler.

Worker ID lists are unique, ordered and bounded to `1..100`. Snapshot values
are `UNKNOWN`, `CONNECTED`, or `DISCONNECTED`. A pending first
verification deliberately projects as `UNKNOWN`; it is not a separately
published lifecycle state. These values are
point-in-time Adapter route observations, not Binding validity, schedulability,
writability, Worker idleness, or proof that the Worker process is alive.
Anonymous physical Channels are not Worker routes. An Adapter restart clears
the process-local verification cache, so the next identity starts from
`UNKNOWN`. There is no application heartbeat; a silent half-open connection is
reported disconnected only after the network stack, close, failure, or a write
detects it. Close-current atomically moves the observed route to disconnected
and asks the physical Server to close that Channel (`1000` for WebSocket, TCP
close for Socket). It preserves the process-local verification cache, so the
existing Client reconnect path can install a new active Channel without
another route verification.

Worker connection state and cached properties are deliberately separate query
surfaces. A properties entry is known only when both `updatedAtMillis` and
`properties` are non-null. Route identity loss, properties capacity eviction,
or Adapter restart produces null fields. Adapter configuration owns two finite
policy blocks:

```yaml
reconnect-verification-retention: 10m
maximum-disconnected-workers: 100000
maximum-encoded-properties-bytes: 67108864
```

The two snapshot events do not call each other and have no atomic join or
shared version. `CONNECTED`, cached properties, current Binding and scheduling
eligibility remain independent facts. A caller needing a combined view invokes
both events and joins by workerId. `updatedAtMillis` is comparable only within
one retained entry lifetime; after UNKNOWN or Adapter restart, the next value is
a new baseline. The Adapter does not automatically probe a Worker or publish
attribute changes to Server or Kernel. Entries are keyed by workerId and do not
repeat the caller-owned WorkerGroup. Both caches use caller-thread maintenance
only: no loader, refresh, listener, scheduler or cleanup thread is installed.

The current Worker event supplies a complete properties snapshot. There is no
partial-update, change-notification, or per-property timestamp contract. This
Adapter observation is never a Kernel write path.

### Result ingress loop

`DeliveryReportDispatcher` owns three finite
`LinkedBlockingQueue<DeliveryReport>` lanes: TASK, SYSTEM, and KERNEL. It is
both their non-blocking multi-producer admission boundary and their one shared
consumer. Each accepted Report is decoded once before admission. The configured
`reportQueueCapacity` is the external soft limit of each lane, so the aggregate
external capacity is three times that value. The TASK Queue alone reserves 100
additional physical positions for the single in-flight batch to return.
SYSTEM and KERNEL admission drops use owner-local cumulative sampling rather
than one warning per Report, so a 10k disconnect wave cannot create a matching
warning storm.

One aggregate availability signal blocks the Report thread while all lanes are
empty. Ingress to any lane wakes it. A rotating cursor selects one non-empty
lane, `poll()` obtains its first item, and `drainTo(...)` takes at most 99 more
from that same lane. Every remote batch is therefore non-empty, FIFO within its
lane, at most 100 items, and homogeneous by `dst`; continuously busy TASK cannot
permanently starve SYSTEM or KERNEL.

```text
select one non-empty lane, then drain up to the 100-Report batch limit
  -> call WorkerDeliveryRemoteApi.results:append once with Report objects
success or semantic rejection -> complete the batch
TASK remote unavailable        -> append exact batch to TASK tail, then backoff
TASK protocol/unclassified     -> drop batch, then backoff
SYSTEM/KERNEL failure          -> drop batch, then backoff
```

All lanes use the one `POST /{adapterId}/results:append` endpoint. The request is
an array of `DeliveryReport` objects, not encoded strings. Server validates the
whole batch before an Owner side effect, rejects mixed or unsupported
destinations, and selects exactly one semantic Owner from the first `dst`: TASK
enters Kernel Task Result truth, SYSTEM enters the Server-local Direct Call
owner, and KERNEL enters the Kernel Worker Serviceability handoff. Owner-local
correlation then interprets opaque `forward` and contributes per-item accepted
or rejected counts.

TASK remote unavailability appends the exact batch to its Queue tail, so Reports
already queued may pass it. TASK has no retry counter or deadline: it is
important, non-superseding completion evidence, while loss of the same Server
normally also stops fresh Command acquisition. Memory and work remain bounded
by the finite external Queue, one in-flight batch, the 100-item retry reserve,
one HTTP call, fixed backoff, and Adapter lifetime. This is continuous
best-effort retransmission, not durable delivery; a lost response or partial
Server application can still produce duplicates. SYSTEM and KERNEL evidence is
time-sensitive and is dropped after any failed submission.

The fixed remote batch limit remains 100 and is independent of queue capacity.
While the remote owner accepts batches, the resident Dispatcher continuously
drains without a configured per-cycle limit. HTTP, logging, and backoff stay
outside the short per-lane admission gates. `reportBackoff` is used only after a
failed submission; normal idle waits indefinitely for ingress. After stop, the
Dispatcher clears current and queued data without another HTTP call.

The Command Queue and three Report Queues remain private to their respective
Dispatchers. Thread interrupt owns shutdown wakeup. Future Report concurrency,
if justified by measurements, must be one bounded executor inside the Report
owner with explicit in-flight, retry-reserve, and shutdown bounds; a Queue must
never imply a dedicated thread. Adapter failure can lose queued Commands or
Reports; there is no ACK, persistent Queue, or exactly-once claim.

A physical Channel close is a reconnectable network fact. It does not tell the
Worker to end its current run. Only a delivered
`ADAPTER/worker.connection.close` Command carries that control meaning.

## Lifecycle

Start:

```text
bind listener
-> Process Manager starts the fixed Report then Command Dispatchers
-> state RUNNING
```

Close:

```text
state STOPPING
-> stop and interrupt the Command loop
-> close listener and every pre-identity, pending-verification, or bound Channel
-> clear the remaining per-Worker route state; closed Channels release their metadata
-> stop Report ingress and interrupt the Report loop
-> join both consumer threads against one shared deadline
-> drop any remaining Command retry and Report current/queued data
-> state CLOSED
```

The aggregate serializes the complete public `start()` and `close()` lifecycle;
a concurrent close caller cannot observe `CLOSED` before the owner that began
    network and Dispatcher shutdown has finished.

`shutdownTimeout` is an owner-local budget, not one Adapter-wide deadline.
Each physical Server computes one deadline for its listener, all child
Channels, child EventLoop group, and acceptor EventLoop; timeout initiates
remaining closes without waiting again and reports `SHUTDOWN_TIMEOUT (21004)`.
The `AdapterProcessManager` then applies one separate shared deadline to its
two Dispatcher threads: every thread is interrupted first, and sequential
joins consume only the same deadline's remainder. A Dispatcher thread that misses
that budget returns `SHUTDOWN_TIMEOUT (21004)`. There is no close-thread Result
flush, so Adapter close is bounded by the physical Server budget and the one
shared Dispatcher join budget rather than a queue drain or additional HTTP request.

The WebSocket protocol accepts text only and normalizes
`TextWebSocketFrame <-> String`. Socket uses
`LineBasedFrameDecoder(1 MiB)`, UTF-8 decoding, and a UNIX `LineEncoder`; it
accepts LF or CRLF and emits one LF-delimited line. Netty owns TCP
fragmentation and coalescing. Both use `WriteTimeoutHandler`; neither resident
Process blocks on a `ChannelFuture`.

Runtime failures use one `WorkerDeliveryAdapterException` with numeric
module-local error codes. Logs use `System.Logger` and must not include command
payload, forward context, secrets, or full Worker result JSON.

## Verification

```text
./gradlew :transport:netty-adapter:test
```
