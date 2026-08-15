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
one `WorkerConnectionMechanism` and `WorkerRouteRegistry`
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
interpretation, first-seen route
verification, Command routing, and Result ingress; its Registry alone owns
verified, pending, active, and Channel-correlation truth. The selected physical
Server owns its listener, EventLoop, all child Channels, complete Pipeline,
framing, physical writes, asynchronous write failures, and close behavior.
These are internal owners, not a public SPI or transport-kind branch.

The supported construction surface is deliberately limited to
`WorkerDeliveryAdapter`, `WorkerDeliveryAdapterManager`,
`NettyAdapterProcessConfig`, and `NettyWorkerDeliveryAdapters`. Java types
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
implementations. A test-only parameterized `NettyWorkerServer` behavior
contract constrains their common lifecycle and normalized-text semantics; the
production implementations do not share a lifecycle helper or base class.

`WorkerDeliveryAdapterManager` manages complete instances: register before
start, start in order, and close in reverse order. Multiple instances in one
JVM are meaningful only when they use different listener endpoints and
different endpoint-manager mailboxes.

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
`src=WORKER` and a non-blank `sourceId`, but does not parse the workerId format.
The identity payload is exactly `"null"`.

Each Adapter process verifies a workerId remotely only the first time its route
directory sees that identity. The first Channel becomes the one pending
verification owner; another initial Channel for the same workerId is physically
closed. Server confirms that the persisted Endpoint Binding points to this
Adapter's `endpointManagerId`. Successful verification atomically records the
workerId in the process-local verified set and activates
`workerId -> current Channel` without an identity ACK. A definite Server 4xx
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

Ordinary disconnect removes only the exact active Channel. The verified workerId
remains cached, so a later identity for the same workerId skips Server
verification and atomically replaces the current Channel. This verified set is
not persistent Endpoint Binding, authentication, authorization, Worker online
truth, or a Property cache. It has no TTL or implicit recheck and is cleared
only when the Adapter closes or restarts. There is currently no unbind operation.

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
mechanism validates the first Report, coordinates optional first verification,
and dynamically derives each inbound Channel's phase from
`WorkerRouteRegistry`. The callback Handler remains installed for the full
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
verified worker IDs, first-verification pending Channels, active
`workerId -> Channel` routes, and Channel correlation as atomic truth. The
connection mechanism selects a route and asks its physical Server to write a
normalized command string. The WebSocket Server emits a text frame; the Socket
Server emits one UTF-8 line. Those Servers also map semantic close reasons to a
WebSocket close frame or TCP close. A verified reconnect replaces the current
Channel for that workerId. Deactivation compares exact Channel identity, so a
delayed close from an old Channel cannot remove its replacement or verified
route.
Different Adapter instances never share a Session, cache, or Channel registry.
Results already sent by an old connection are still eligible evidence; Kernel
Result Routing decides whether their `forward` context remains valid.

## Delivery Processes

### Command consumption round

`DeliveryCommandProcess` owns one private queue and one fixed remote path.
Every request to `commands:consume` returns a map from exactly one Server-owned
source. CONTROL_ONLY has strict priority at Server; Adapter never requests two
hashes and never merges them. This is remote acquisition priority, not local
preemption: commands already present in the Adapter FIFO remain ahead, a full
local queue postpones the next Server consume, and an already running Worker
Handler is unaffected. Sustained CONTROL_ONLY traffic can therefore delay TASK
mailbox acquisition by policy.

```text
while the queue is below its soft capacity
  -> consume at most the configured limit from commands:consume
  -> ingress the complete bounded batch

consume one queue snapshot
for each command exactly once this round
  -> expired: remove; only expired TASK creates 23002 evidence
  -> no active writable Worker Channel: rotate to queue tail
  -> physical Server write started: remove
  -> SYSTEM -> ADAPTER at @adapter: execute fixed adapter.probe
```

TASK accepts `TASK -> WORKER`. CONTROL_ONLY accepts `SYSTEM -> WORKER` and
`SYSTEM -> ADAPTER` only at `@adapter`. No active Channel is temporary while
the deadline remains live. CONTROL_ONLY expiry creates no synthetic result;
the Server waiter owns timeout. The queue has no workerId index, and its soft
capacity is a backpressure target rather than delivery truth. Adapter does not
read Worker score or recheck pause: Server admission is the only current
CONTROL_ONLY eligibility observation.

### Result ingress round

`DeliveryReportProcess` owns one private queue, one pending batch, and the
single `results:append` path. Qualified TASK and SYSTEM reports preserve their
original encoded JSON and enter through the same concrete `ingress(...)`
operation.

```text
pending batch exists -> retry it first
otherwise            -> drain the queue once
submit one mixed encoded-result batch to results:append
```

Server routes each Report by `dst`: TASK enters Kernel Result truth and SYSTEM
completes the Server-local Control waiter. Remote unavailability retains the
whole mixed pending batch; protocol rejection drops it. Normal close performs
one bounded best-effort final submit. Command and Report rounds remain
independent. TASK and SYSTEM do not have separate retry policies inside the
Adapter: a late CONTROL_ONLY Report may be retried with the mixed batch and is
then rejected by Server after its waiter has ended.

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
-> clear verified, pending, and active route-directory state
-> AdapterProcessManager quiesces AFTER_NETWORK_CLOSE processes
-> AdapterProcessManager closes its scheduler and finishes processes in reverse order,
   including one bounded final Result flush
-> state CLOSED
```

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
