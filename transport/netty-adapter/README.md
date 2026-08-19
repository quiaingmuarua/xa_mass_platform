# XA Mass Netty Worker Delivery Adapter

Status: Java 21 multi-endpoint Adapter with three explicit Netty-specific
owners: Adapter scheduling, shared connection mechanism, and complete
WebSocket / line-Socket physical Servers.

The production owner cut is frozen. Hardening may refine an owner's local
behavior and proof, but must not introduce another lifecycle owner, shared
Server base, Session, Bridge, or protocol-extension framework.

This module is a plain `java-library`. It does not depend on Spring, Server,
Kernel, Redis, score, or Pacer code. It reaches the Server Worker Delivery
batch API through three owner-local Remote APIs backed by one Adapter-private
`WorkerDeliveryHttpClient`.

## Instance Boundary

Server constructs the two finite physical protocol variants through
`NettyWorkerDeliveryAdapters`, which returns only the public
`WorkerDeliveryAdapter` contract. Both variants instantiate the same
package-private `NettyWorkerDeliveryAdapter`; every instance independently
owns:

```text
adapterId = endpointManagerId
listenHost + listenPort
one `WorkerConnectionMechanism`, `WorkerRouteRegistry`, and properties cache
one sharable `WorkerConnectionInboundHandler` adapting Netty callbacks
one complete `WebSocketNettyWorkerServer` or `SocketNettyWorkerServer`
one `DeliveryCommandProcess` with one private Command queue
one `DeliveryReportProcess` with one private Result queue and pending batch
one `AdapterProcessManager` owning the finite Process set and its scheduler
```

The common Adapter aggregate owns lifecycle and network ordering.
`AdapterProcessManager` owns Process identity validation, its two scheduler
threads, safe round invocation, phase-local quiescence, and reverse-order
finish. The Process set is fixed for the Adapter lifetime; the Manager stores
no per-Process Future and exposes no individual stop operation. It knows
nothing about the network or HTTP. The Command Process owns the unified remote
Command path, delivery/rotation, expiry, and one local queue. The Report
Process owns the unified Result path, local Result acceptance, one pending
batch, and one local queue. Each `FiniteQueue` is
business-neutral process infrastructure and owns only thread-safe FIFO storage
with soft-capacity ingress; it is never passed between owners. The stateless
inbound Handler only forwards normalized text, inactive, and failure callbacks.
The shared connection mechanism owns identity
interpretation, route verification, Command routing, and Result ingress; its
Registry alone owns
the single per-Worker route truth. Each claimed Channel carries only its Worker
identity as Adapter-local Netty metadata for inbound correlation. The selected
physical Server owns its listener, EventLoop, all child Channels, complete
Pipeline, framing, physical writes, asynchronous write failures, and close
behavior. These are internal owners, not a public SPI or transport-kind branch.

The supported construction surface is deliberately limited to
`WorkerDeliveryAdapter`, `WorkerDeliveryAdapterManager`,
`NettyAdapterProcessConfig`, `NettyWorkerRouteCacheConfig`,
`NettyWorkerPropertiesCacheConfig`, and `NettyWorkerDeliveryAdapters`. Java types
under `netty.internal` are `public` only where repository packages must
collaborate without JPMS; they are repository-internal and carry no external
compatibility promise.

Implementation packages follow owner responsibility rather than protocol
similarity:

```text
netty/
  finite public factory + one package-private Adapter aggregate
netty/internal/process/
  DeliveryCommandProcess, DeliveryReportProcess, and private FiniteQueues
netty/internal/connection/
  one Netty callback adapter + shared connection semantics + pure route truth
netty/internal/remote/
  three owner-local Remote APIs + one Adapter-private mechanical HTTP client
netty/internal/network/
  internal Server contract + complete WebSocket and Socket physical owners
```

Java collaborators crossing those owner packages are `public` only for module
assembly and remain under `netty.internal`; they are not supported construction
contracts, and Server is guarded from importing them.
`WorkerConnectionInboundHandler` has only one dependency: the connection
mechanism. It owns no codec, route, verification, Result, or physical network
behavior. The shared connection mechanism exposes a concrete `deliver(...)`
owner operation and depends only
on `WorkerRouteRemoteApi` plus the concrete `DeliveryReportProcess`. It sees normalized
strings and Netty Channels as route addresses, but all physical write/close
operations return through `NettyWorkerServer`; it does not see WebSocket
frames, Socket lines, handshake types, listener resources, or Pipeline
mutation.

`DeliveryCommandRemoteApi`, `DeliveryReportRemoteApi`, and
`WorkerRouteRemoteApi` own their specific path, wire JSON, expected HTTP status,
and owner failure classification. `WorkerDeliveryHttpClient` is created once
inside each Adapter and shared only by those three Remote APIs. It owns the JDK
HTTP client, base URI, request timeout, headers, path encoding, raw request,
and expected-status enforcement; it imports no Delivery DTO or owner HTTP
codec. Processes and connection mechanism never see the Client, URL, status,
or HTTP JSON contract.

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
Throughput for one endpoint is controlled by consume limit, command queue
capacity, and loop intervals.

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

Each Adapter process verifies a workerId remotely when it has no current route
or retained verification evidence. The first Channel atomically claims the
single pending Route entry; another initial Channel for the same workerId is
physically closed. Server confirms that the workerId's current Endpoint Binding
points to this Adapter's `endpointManagerId`. Successful verification
atomically turns that same entry into `workerId -> current Channel` without an
identity ACK. No WorkerGroup metadata is stored on the Channel or route. A
definite Server 4xx
rejection causes the Adapter to write
`DeliveryCommand(ADAPTER -> WORKER, worker.connection.close, payload="null")`
and close the physical Channel after the write flushes. Remote API unavailability
or a 5xx response only closes the physical Channel, allowing the Worker Client
to consume its current-Endpoint reconnect budget.

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
Worker-owned `3...`. Both `dst=TASK` and `dst=SYSTEM` enter the same Result
queue and preserve the original encoded JSON. A full or closed queue drops the
Result. TASK backpressure closes the exact Channel; best-effort SYSTEM
backpressure keeps it usable.

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

### Command consumption round

`DeliveryCommandProcess` owns one private queue and one fixed remote path.
Server may place a bounded prefix from its Adapter Direct FIFO in a
`commands:consume` response. Remaining capacity first comes from one consume of
the shared Worker Command Hash, whose fields may contain TASK or SYSTEM
Commands. If capacity still remains, Server may add one bounded Kernel
Serviceability Adapter snapshot Command. This is remote acquisition priority,
not local preemption: commands already present in the Adapter FIFO remain
ahead, a full local queue postpones the next Server consume, and an already
running Worker Handler is unaffected. Sustained higher-priority Commands may
starve Serviceability acquisition by design.

```text
while the queue is below its soft capacity
  -> consume at most the configured limit from commands:consume
  -> ingress the complete bounded batch

consume one queue snapshot
for each command exactly once this round
  -> expired TASK: atomically offer the 23002 TASK Report plus one KERNEL
     worker-delivery.expired Report, then remove
  -> any other expired Command: remove without synthetic evidence
  -> no active writable Worker Channel: rotate to queue tail
  -> physical Server write started: remove
  -> dst=ADAPTER: ignore the entry key and dispatch through the immutable map
```

TASK accepts `TASK -> WORKER`. DIRECT_CALL uses `SYSTEM -> WORKER` or
`SYSTEM -> ADAPTER`. Worker Serviceability uses only `KERNEL -> ADAPTER` with
`platform.adapter.worker-connections.snapshot`; every other KERNEL Adapter
event is rejected. A Worker Command entry key is its workerId; an Adapter
Command entry key is opaque and ignored. No active Channel is temporary while
the deadline remains live. DIRECT_CALL expiry creates no synthetic result;
the Server waiter owns timeout. The queue has no workerId index, and its soft
capacity is a backpressure target rather than delivery truth. Adapter does not
read Worker score or interpret Serviceability policy.

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
route-cache:
  reconnect-verification-retention: 10m
  maximum-disconnected-workers: 100000
properties-cache:
  maximum-encoded-bytes: 67108864
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
partial-update or per-property timestamp contract. A future patch event must
start from a complete baseline, define deletion tombstones, atomically merge to
another complete Map, and advance the one Worker-level `updatedAtMillis`.

### Result ingress round

`DeliveryReportProcess` owns one private queue, one pending batch, and the
single `results:append` path. Qualified Worker TASK/SYSTEM Reports and
Adapter-produced KERNEL Reports enter through the same concrete `ingress(...)`
operation. Worker Reports preserve their original encoded JSON. Result queue
capacity is at least two so the one logical expired-TASK pair is accepted or
dropped together.

```text
pending batch exists -> retry it first
otherwise            -> drain the queue once
submit one mixed encoded-result batch to results:append
```

Server selects the receiving owner by Report `dst`: TASK enters Kernel Task
Result truth, SYSTEM enters the Server-local Direct Call owner, and KERNEL
enters the Kernel Worker Serviceability result handoff. Owner-local correlation
then interprets opaque `forward`. Remote unavailability retains the whole mixed
pending batch; protocol rejection drops it. Normal close performs one bounded
best-effort final submit. Command and Report rounds remain independent. The
destinations do not have separate retry policies inside the Adapter: a late
DIRECT_CALL Report may be retried with the mixed batch and is then rejected by
Server after its waiter has ended.

Both queues are finite, soft-capacity, and private to their Process.
`estimatedSize` is advisory. Adapter failure can lose queued commands or
results; there is no ACK, persistent pending queue, or exactly-once claim.

A physical Channel close is a reconnectable network fact. It does not tell the
Worker to end its current run. Only a delivered
`ADAPTER/worker.connection.close` Command carries that control meaning.

## Lifecycle

Start:

```text
bind listener
-> state RUNNING
-> AdapterProcessManager starts its scheduler and every finite Process entry
```

Close:

```text
state STOPPING
-> AdapterProcessManager quiesces BEFORE_NETWORK_CLOSE processes
-> close listener and every pre-identity, pending-verification, or bound Channel
-> clear the remaining per-Worker route state; closed Channels release their metadata
-> AdapterProcessManager quiesces AFTER_NETWORK_CLOSE processes
-> AdapterProcessManager closes its scheduler and finishes processes in reverse order,
   including one bounded final Result flush
-> state CLOSED
```

The aggregate serializes the complete public `start()` and `close()` lifecycle;
a concurrent close caller cannot observe `CLOSED` before the owner that began
network and Process shutdown has finished.

`shutdownTimeout` is an owner-local budget, not one Adapter-wide deadline.
Each physical Server computes one deadline for its listener, all child
Channels, and EventLoop; timeout initiates remaining closes without waiting
again and reports `SHUTDOWN_TIMEOUT (21004)`. The Adapter Process scheduler then gets
its own deadline, and `shutdownNow()` may consume only that deadline's
remainder. A scheduler that misses its budget prevents a potentially blocking
final Report flush; the Result queue has already stopped accepting and shutdown
returns the timeout failure after the other cleanup steps. On the normal path,
the final flush remains best effort and is bounded by the HTTP client request
timeout. Consequently the normal worst-case close envelope is the sum of the
physical Server budget, scheduler budget, and one HTTP final-flush request,
not an unbounded wait.

The WebSocket protocol accepts text only and normalizes
`TextWebSocketFrame <-> String`. Socket uses
`LineBasedFrameDecoder(1 MiB)`, UTF-8 decoding, and a UNIX `LineEncoder`; it
accepts LF or CRLF and emits one LF-delimited line. Netty owns TCP
fragmentation and coalescing. Both use
`WriteTimeoutHandler`; neither scheduled Process blocks on a `ChannelFuture`.

Runtime failures use one `WorkerDeliveryAdapterException` with numeric
module-local error codes. Logs use `System.Logger` and must not include command
payload, forward context, secrets, or full Worker result JSON.

## Verification

```text
./gradlew :transport:netty-adapter:test
```
