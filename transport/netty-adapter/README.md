# XA Mass Netty Worker Delivery Adapter

Status: Java 21 multi-endpoint Adapter runtime with Netty WebSocket and
line-delimited Socket implementations.

This module is a plain `java-library`. It does not depend on Spring, Server,
Kernel, Redis, score, or Pacer code. It reaches the Server Worker Delivery
batch API through its `WorkerDeliveryGatewayClient`.

## Instance Boundary

One concrete Adapter instance owns:

```text
adapterId = endpointManagerId
listenHost + listenPort
one Netty listener
one current Channel per workerId
one bounded WorkerCommand queue
one scheduled Command Loop
one bounded encoded WorkerResult queue
one scheduled Result Loop
```

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

Socket uses one compact JSON value per UTF-8 line. Both transports start without
an active Worker route. The first inbound value must be a strict Worker
connection Bind frame:

```json
{
  "workerId":"3d813cbb-47fb-4ea8-a5be-6bf4c4a99089"
}
```

A new Channel pauses reads while the Adapter asks Server whether the persisted
Endpoint Binding for `workerId` points to this Adapter's `endpointManagerId`.
Successful route verification activates `workerId -> current Channel` and
resumes reads; missing, conflicting, or unavailable Binding closes the Channel.
Verification happens for every new connection. The Adapter has no identity or
Binding cache and does not refresh Worker Properties.

An unverified Channel is never visible to the Command Loop. During verification,
the transport boundary may retain one pending Result that immediately follows
Bind; it is processed in order only after verification succeeds. A second
pre-verification value is a protocol violation. This is not a second Adapter
result queue.

After connection activation:

```text
Adapter -> Worker : direct WorkerCommand JSON
Worker  -> Adapter: direct WorkerResult JSON
```

There is no outer frame DTO. Adapter identity comes from its listener and
mailbox configuration, not from a URL path or message field.

Each registry stores the actual Netty `Channel`. A newly activated connection
replaces the current Channel for that workerId. Deactivation compares workerId
and Channel identity, so a delayed close from an old Channel cannot remove its
replacement.
Results already sent by an old connection are still eligible evidence; Kernel
Result Routing decides whether their `forward` context remains valid.

## Command Loop

The Command Loop owns the temporary command queue:

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
copies the command `messageId`, `src` as result `dst`, `messageType`, and
`forward`; it uses payload `"null"`.

No active Channel is a temporary retry condition while the command remains
live. A send-started failure is ambiguous and must not fabricate Adapter
rejection evidence.

## Result Loop

Netty handlers decode a direct `WorkerResult` only to enforce the Worker
boundary: Worker-originated results may use `200` or Worker-owned `3...`. A
valid result is queued using its original encoded JSON; Adapter does
not rebuild or re-encode it.

Adapter-generated `COMMAND_EXPIRED` enters the same bounded queue. The Result
Loop runs at
`resultSubmitInterval`:

```text
pending batch exists -> retry it
otherwise            -> drain current queue once
submit one encoded-result batch to Server
```

The batch request has no caller-provided source field. The Server endpoint is
the trusted Adapter ingress and accepts valid success, Worker-failure, and
Adapter-rejection results targeting `TASK`. Point Worker result ingress
separately rejects Adapter-rejection outcomes.

Gateway protocol rejection drops the pending batch. Network or Server
unavailability retains it for a later interval. Command consumption and result
submission are independent loops; result failure does not stop command
forwarding.

Both queues are bounded and process-local. Adapter process failure can lose
queued commands or results. Existing TaskItem claims, Worker leases, and
Result Routing fences remain the convergence mechanism. There is no ACK,
persistent pending queue, or exactly-once claim.

## Lifecycle

Start:

```text
bind listener
-> state RUNNING
-> schedule Command Loop and Result Loop
```

Close:

```text
state STOPPING
-> stop Command Loop
-> close listener and Channels
-> stop Result Loop
-> stop accepting Worker results
-> bounded final result flush
-> state CLOSED
```

The WebSocket handler accepts text only. Socket uses
`LineBasedFrameDecoder(1 MiB)`, UTF-8 decoders/encoders, and accepts LF or
CRLF. Netty owns TCP fragmentation and coalescing. Both use
`WriteTimeoutHandler`; neither loop blocks on a `ChannelFuture`.

Runtime failures use one `WorkerDeliveryAdapterException` with numeric
module-local error codes. Logs use `System.Logger` and must not include command
payload, forward context, secrets, or full Worker result JSON.

## Verification

```text
./gradlew :transport:netty-adapter:test
```
