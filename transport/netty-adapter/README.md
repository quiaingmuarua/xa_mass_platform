# XA Mass Netty Worker Delivery Adapter

Status: Java 21 multi-endpoint Adapter runtime with one shared delivery
mechanism and finite Netty WebSocket / line-Socket physical protocols.

This module is a plain `java-library`. It does not depend on Spring, Server,
Kernel, Redis, score, or Pacer code. It reaches the Server Worker Delivery
batch API through its `WorkerDeliveryGatewayClient`.

## Instance Boundary

Server constructs the two finite physical protocol variants through
`NettyWorkerDeliveryAdapters`, which returns only the public
`WorkerDeliveryAdapter` contract. Both variants instantiate the same
package-private `NettyWorkerDeliveryAdapter`; every instance independently
owns:

```text
adapterId = endpointManagerId
listenHost + listenPort
one `NettyServerLifecycle` using one finite physical protocol
one Netty listener and EventLoop
all physical child Channels, including pre-identity, pending-verification,
and bound Channels
one current Channel per workerId
one bounded DeliveryCommand queue
one scheduled DeliveryCommand Pump
one bounded encoded DeliveryReport queue
one scheduled DeliveryReport Pump
```

The common Adapter aggregate owns lifecycle, scheduler, queues, Pumps, and
route state. `NettyServerLifecycle` owns listener, EventLoop, and every child
Channel. The selected finite `AdapterNetworkProtocol` owns only Pipeline
installation, frame normalization, and physical close behavior. It is a sealed
internal boundary, not a generic Adapter SPI or transport-kind branch.

Implementation packages follow owner responsibility rather than protocol
similarity:

```text
netty/
  finite public factory + one package-private Adapter aggregate
netty/internal/gateway/
  DeliveryCommand target port, Result ingress buffer, and both Gateway Pumps
netty/internal/connection/
  verified/pending/active route owner and common identity/bound handlers
netty/internal/network/
  one listener/EventLoop/child-Channel owner plus finite physical protocols
```

Java collaborators crossing those owner packages are `public` only for module
assembly and remain under `netty.internal`; they are not supported construction
contracts, and Server is guarded from importing them. Gateway Pumps still see
only the transport-neutral `DeliveryCommandTarget`. The shared connection
mechanism sees only normalized strings plus the finite physical close port; it
does not see WebSocket frames, Socket lines, handshake types, or listener
resources.

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
and close the physical Channel after the write flushes. Gateway unavailability
or a 5xx response only closes the physical Channel, allowing the Worker Client
to consume its current-Endpoint reconnect budget.

An unverified Channel is never visible to the DeliveryCommand Pump. Reads stay
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

After the physical Pipeline normalizes input to `String`, the same identity
Handler validates the first Report, coordinates optional first verification
with the Adapter's route directory, and replaces itself with the same bound
Handler after activation. Pipeline membership represents connection phase;
there is no lifecycle phase state machine. The fixed
identity Report is not routed through an
event registry or plugin dispatcher. Adapter-directed Reports never enter the
Server Result queue. Repeated identity and unknown Adapter events on an
established connection are logged and dropped. Before identity, a malformed,
invalid, or non-identity Report closes the physical Channel.

Once bound, malformed JSON, `SYSTEM`, repeated identity, unknown Adapter
events, mismatched `src/sourceId`, and Worker-originated `2...` outcomes are
logged and dropped without closing the Channel. Only `TASK` Reports with
`src=WORKER`, the bound workerId, and `200` or Worker-owned `3...` outcomes
enter the bounded Result queue, preserving their original JSON. A
full or closed Result queue drops the current Result and physically closes the
Channel as process-local backpressure.

Each Adapter constructs one Worker route directory. It owns
the process-local verified worker IDs, first-verification pending Channels,
and active `workerId -> Channel` routes. It writes normalized command strings;
the selected physical Pipeline produces a WebSocket text frame or one UTF-8
line. Semantic close reasons are mapped by that protocol to a WebSocket close
frame or TCP close. A verified reconnect replaces the current Channel for that
workerId. Deactivation compares workerId and Channel identity, so a delayed
close from an old Channel cannot remove its replacement or verified route.
Different Adapter instances never share a Session, cache, or Channel registry.
Results already sent by an old connection are still eligible evidence; Kernel
Result Routing decides whether their `forward` context remains valid.

## DeliveryCommand Pump

The DeliveryCommand Pump owns the temporary command queue:

```text
when a full consume batch fits
  -> consume at most commandConsumeLimit commands from Server

for each command present at round start
  -> expired: remove and enqueue Adapter COMMAND_EXPIRED best-effort
  -> no active writable Channel: rotate to queue tail
  -> writeAndFlush started: remove
  -> write initiation/future failure: close exact Channel, result UNKNOWN
```

The queue has no workerId index. One round observes each queued command once.
The Server mailbox already partitions by endpointManagerId, while workerId is
the Channel route coordinate.

Adapter holds a consumed command until send starts or the deadline expires.
An Adapter-generated `WorkerDeliveryAdapterErrorCode.COMMAND_EXPIRED` (`23002`)
uses `DeliveryReport.fromCommand`, declares `src=ADAPTER` and
`sourceId=adapterId`, and copies the Command message type plus opaque forward
context. Its payload is `"null"`.

No active Channel is a temporary retry condition while the command remains
live. A send-started failure is ambiguous and must not fabricate Adapter
rejection evidence.

## DeliveryReport Pump

Netty handlers strictly decode every direct `DeliveryReport`. Results targeting
`ADAPTER` stay local. Only bound `TASK` Results using `200` or Worker-owned
`3...` are queued, using their original encoded JSON so Adapter does not
rebuild payload or forward context. `SYSTEM` has no Adapter queue consumer and
is dropped.

Adapter-generated `COMMAND_EXPIRED` enters the same bounded queue. The
DeliveryReport Pump runs at
`resultSubmitInterval`:

```text
pending batch exists -> retry it
otherwise            -> drain current queue once
submit one encoded-result batch to Server
```

The batch wrapper has no caller-provided source field. Each encoded Report
declares its producer. Server accepts `WORKER` success/failure Reports and only
accepts `ADAPTER` `2...` Reports whose `sourceId` matches the batch
`endpointManagerId`. Point Worker Report ingress separately requires
`WORKER + path workerId`.

Gateway protocol rejection drops the pending batch. Network or Server
unavailability retains it for a later interval. Command consumption and Report
submission are independent pumps; Report failure does not stop command
forwarding.

Both queues are bounded and process-local. Adapter process failure can lose
queued commands or results. Existing TaskItem claims, Worker leases, and
Result Routing fences remain the convergence mechanism. There is no ACK,
persistent pending queue, or exactly-once claim.

A physical Channel close is a reconnectable network fact. It does not tell the
Worker to end its current run. Only a delivered
`ADAPTER/worker.connection.close` Command carries that control meaning.

## Lifecycle

Start:

```text
bind listener
-> state RUNNING
-> schedule DeliveryCommand and DeliveryReport Pumps
```

Close:

```text
state STOPPING
-> stop DeliveryCommand Pump
-> close listener and every pre-identity, pending-verification, or bound Channel
-> clear verified, pending, and active route-directory state
-> stop DeliveryReport Pump
-> stop accepting Worker results
-> bounded final result flush
-> state CLOSED
```

The WebSocket protocol accepts text only and normalizes
`TextWebSocketFrame <-> String`. Socket uses
`LineBasedFrameDecoder(1 MiB)`, UTF-8 decoding, and a UNIX `LineEncoder`; it
accepts LF or CRLF and emits one LF-delimited line. Netty owns TCP
fragmentation and coalescing. Both use
`WriteTimeoutHandler`; neither pump blocks on a `ChannelFuture`.

Runtime failures use one `WorkerDeliveryAdapterException` with numeric
module-local error codes. Logs use `System.Logger` and must not include command
payload, forward context, secrets, or full Worker result JSON.

## Verification

```text
./gradlew :transport:netty-adapter:test
```
