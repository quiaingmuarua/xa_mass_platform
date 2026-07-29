# XA Mass Netty Worker Delivery Adapter

Status: Java 21 multi-endpoint Adapter runtime with Netty WebSocket and
line-delimited Socket implementations.

The module owns complete active Adapter instances:

```text
Map<adapterId, config>
  -> create WebSocketWorkerDeliveryAdapter or SocketWorkerDeliveryAdapter
  -> WorkerDeliveryAdapterManager.register(instance)
  -> manager.start()
  -> independent Netty listener + Command/Result loops per instance
  -> manager.close()
```

It is a plain `java-library`. It does not depend on Spring, Spring Boot,
Server, Kernel, Redis, score, or Pacer code.

Runtime failures use the module's single
`WorkerDeliveryAdapterException` with numeric
`WorkerDeliveryAdapterErrorCode` values in `20000..29999`, through the narrow
[`foundation_jvm`](../../foundation_jvm/README.md) contract. Gateway
unavailability, malformed Gateway responses, interrupted delivery, listener
startup, and interrupted shutdown are code categories, not exception
subclasses. Constructor precondition failures remain
`IllegalArgumentException`; they are programming errors rather than runtime
Adapter evidence.

Adapter logs use JDK `System.Logger` directly:

```text
errorCode=22001 operation=gateway.consumeCommands adapterId=<id> message=...
```

Logs and tracing call sites may add Adapter identifiers and complete execution
context. The exception itself contains only code, operation, message, cause,
and stack. Neither exceptions nor logs include `opaqueItem`,
`opaqueResultContext`, secrets, or complete business payloads.

## Instance Boundary

One concrete Adapter instance owns:

```text
adapterId = endpointManagerId
listenHost + listenPort
one scheduled Command Loop
one bounded in-memory Command Queue
one scheduled Result Loop
one bounded in-memory Result Queue
one current Netty Channel per WorkerId
```

Multiple instances in one JVM are useful only when they expose different
network endpoints and consume different endpoint-manager mailboxes. Adapter
identity is determined by the listener host/port and Worker declaration; it is
not encoded into a connection path or business message.

WebSocket instances use one fixed path:

```text
/api/v1/worker-delivery/websocket
```

Socket instances accept newline-delimited messages on their configured TCP
listener. Both transports receive `WorkerConnectionBind` as the first message,
then bind the declared WorkerId to the current Netty Channel. The old
WorkerId-bearing WebSocket path is not accepted.

Do not run two instances for the same `endpointManagerId`. Throughput for one
endpoint is tuned with `commandConsumeLimit`, `commandQueueCapacity`, and
`commandLoopInterval`, not by starting competing mailbox consumers.

`system-polling` is a Server point-API binding and cannot be registered as an
active Adapter.

## Stable Contracts

```text
WorkerDeliveryAdapter
  adapterId, state, start, close

WorkerDeliveryAdapterManager
  register complete instances, look them up, start in order, close in reverse

WorkerDeliveryGatewayClient
  consume one bounded command Map and append one result batch through Server HTTP

transport-private connection registry
  retain the current Netty Channel for each WorkerId

WorkerCommandDelivery
  narrow Command Loop seam for deliver(workerId, command)

WorkerConnectionMessageDispatcher
  immutable messageType -> handler dispatch

TaskItemResultMessageHandler
  validate Worker-owned outcomes and offer SeedResult to the Result Queue
```

The Manager does not deserialize configuration or construct Adapters. Server
composition converts each configured JSON tree into a concrete instance, then
registers that instance.

A connection registry is an Adapter implementation detail, not a cross-type
contract or persistence boundary. Each Netty transport stores the actual
process-local transport directly as `workerId -> Netty Channel`; it does not
insert a generic connection wrapper between the registry and Netty.
A live Channel is intentionally not Redis-serializable. A future distributed
Adapter ownership record would be separate evidence such as instance identity
and lease time, not a serialized connection registry.

A new Worker connection replaces the current connection for its WorkerId.
Unbind compares both `workerId` and connection instance, so a delayed close
from the replaced connection cannot remove the replacement. Results already
produced through an old connection remain valid evidence; Kernel
ResultContext and Worker lease fences decide whether they can affect truth.

## Message Boundary

Long-lived transports use two protocol phases:

```text
first inbound message
  -> WORKER_BIND
  -> bind workerId to current Channel

outbound WorkerCommandEnvelope
  -> TaskItemCommandMessage
  -> TASK_ITEM_COMMAND message

inbound TASK_ITEM_RESULT message
  -> TaskItemResultMessage
  -> immutable dispatcher
  -> TaskItemResultMessageHandler
  -> bounded Result Queue
```

Bind is handled by each transport before the business dispatcher. The message
family is a connection protocol, not another Kernel runtime or result truth.
Polling continues to use `WorkerCommandEnvelope` and `SeedResult` directly
through the Server point HTTP API.

Handlers are installed once when an Adapter instance is assembled. The
dispatcher has no runtime registration, discovery, fallback, or generic JSON
payload. The first implementation installs only the Task Item result handler.
Trusted Adapter-generated `3001` evidence bypasses Worker message handling and
enters the Adapter's pending result path directly.

## Command And Result Loops

Each Adapter owns two independent scheduled mechanisms:

```text
Command Loop
  -> refill only when one complete consume batch fits
  -> consume at most commandConsumeLimit commands through Server batch HTTP
  -> rotate the current Command Queue once
  -> retain commands whose Worker has no active writable Channel
  -> drop expired commands and best-effort enqueue 3001
  -> remove a command as soon as writeAndFlush starts

Result Loop
  -> retry one pending result batch first
  -> otherwise drain the current Result Queue
  -> issue at most one Server append request per resultSubmitInterval
```

The Server runtime acquires a random bounded set of distinct Worker fields
from the sparse HASH. Adapter command acquisition is stateless between rounds
and assumes no FIFO, priority, stable-order, or global-fairness semantics. Once
consumed, the Adapter Command Queue is the temporary owner until send starts or
the command expires. It has no WorkerId index; a round observes and rotates
each command that was present at round start exactly once.

Delivery evidence remains:

```text
STARTED
  writeAndFlush accepted the command into the selected Channel send path
  -> remove it from the Command Queue

RETRY_LATER
  no active writable Channel is currently available
  -> retain the command at the queue tail

UNKNOWN
  write initiation failed or its asynchronous outcome became ambiguous
  -> remove the command and generate no 3xxx evidence
```

Worker-originated results enter the Result Queue directly from Netty handlers.
Command acquisition and forwarding continue while result submission is
temporarily unavailable. The Result Loop retains one failed batch and retries
it before draining new results; it never submits a second batch in the same
tick.

Both queues and pending retry state are process-local and bounded. Shutdown
stops the Command Loop, closes the listener and Channels, stops the Result
Loop, then performs a bounded final result flush. Remaining commands are
dropped for claim/lease expiry recovery. Process failure can still lose local
commands or results; Item claims and Worker lease fences remain the Kernel
convergence boundary.

## Netty Transports

Each instance owns an embedded Netty listener with one I/O event-loop thread
and a two-thread scheduled executor: one thread can run the Command Loop while
the other runs the Result Loop. There is no per-command executor and neither
loop blocks on a `ChannelFuture`.

The WebSocket handler accepts only text frames. Its first frame is Bind; after
binding, it decodes connection business messages and invokes the static
dispatcher.

The Socket pipeline is:

```text
LineBasedFrameDecoder(1 MiB)
-> UTF-8 StringDecoder
-> UTF-8 StringEncoder
-> SocketWorkerHandler
```

The first line is Bind and each subsequent line is one connection business
message. Commands are written as compact JSON followed by `\n`; reads accept
both `\n` and `\r\n`. Netty owns TCP fragmentation and coalescing.

Both connection registries store current Netty Channels directly. The
transport-neutral `WorkerCommandLoop` only calls `WorkerCommandDelivery`; it
does not import Netty, WebSocket, Socket, bind/unbind, or close reasons.
`WorkerResultLoop` independently owns timed batch submission and pending retry.
Outcome validation stays in the installed message handler, and queue mechanics
stay in the bounded Result Queue.

The JDK `HttpClient` Gateway implementation also lives in this module. Its
private HTTP DTOs match the Server batch API but are not part of the shared
Worker protocol.

## Verification

```text
./gradlew :transport:netty-adapter:test
```
