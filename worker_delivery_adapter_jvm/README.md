# XA Mass JVM Worker Delivery Adapter

Status: Java 21 multi-endpoint Adapter runtime with a Netty WebSocket
implementation.

The module owns complete active Adapter instances:

```text
Map<adapterId, config>
  -> create WebSocketWorkerDeliveryAdapter
  -> WorkerDeliveryAdapterManager.register(instance)
  -> manager.start()
  -> independent Netty listener + mailbox loop per instance
  -> manager.close()
```

It is a plain `java-library`. It does not depend on Spring, Spring Boot,
Server, Kernel, Redis, score, or Pacer code.

## Instance Boundary

One `WebSocketWorkerDeliveryAdapter` owns:

```text
adapterId = endpointManagerId
listenHost + listenPort
one WorkerCommand mailbox cursor
one scheduled consume loop
one bounded delivery executor
one current Netty Channel per WorkerId
one bounded result buffer
```

Multiple instances in one JVM are useful only when they expose different
network endpoints and consume different endpoint-manager mailboxes. Adapter
identity is determined by the listener host/port and Worker declaration; it is
not added to the WebSocket path.

All instances use:

```text
/api/v1/worker-delivery/websocket/workers/{workerId}
```

Do not run two instances for the same `endpointManagerId`. Throughput for one
endpoint is increased with `scanCount`, `deliveryParallelism`, and
`dispatchInterval`, not by starting competing mailbox consumers.

`system-polling` is a Server point-API binding and cannot be registered as an
active Adapter.

## Stable Contracts

```text
WorkerDeliveryAdapter
  adapterId, state, start, close

WorkerDeliveryAdapterManager
  register complete instances, look them up, start in order, close in reverse

WorkerDeliveryGatewayClient
  consume one command page and append one result batch through Server HTTP

WebSocket-private WorkerConnectionRegistry
  retain the current Netty Channel for each WorkerId

WorkerConnectionMessageDispatcher
  immutable messageType -> handler dispatch

TaskItemResultMessageHandler
  validate Worker-owned outcomes and offer SeedResult to the bounded buffer
```

The Manager does not deserialize configuration or construct Adapters. Server
composition converts each configured JSON tree into a concrete instance, then
registers that instance.

A connection registry is an Adapter implementation detail, not a cross-type
contract or persistence boundary. The WebSocket implementation stores the
actual process-local transport directly as `workerId -> Netty Channel`; it
does not insert a generic connection wrapper between the registry and Netty.
A live Channel is intentionally not Redis-serializable. A future distributed
Adapter ownership record would be separate evidence such as instance identity
and lease time, not a serialized connection registry.

A new Worker connection replaces the current connection for its WorkerId.
Unbind compares both `workerId` and connection instance, so a delayed close
from the replaced connection cannot remove the replacement. Results already
produced through an old connection remain valid evidence; Kernel
ResultContext and Worker lease fences decide whether they can affect truth.

## Message Boundary

Long-lived transports use the shared flat `WorkerConnectionMessage` union:

```text
outbound WorkerCommandEnvelope
  -> TaskItemCommandMessage
  -> TASK_ITEM_COMMAND text frame

inbound TASK_ITEM_RESULT text frame
  -> TaskItemResultMessage
  -> immutable dispatcher
  -> TaskItemResultMessageHandler
  -> bounded SeedResult buffer
```

The message family is a connection protocol, not another Kernel runtime or
result truth. Polling continues to use `WorkerCommandEnvelope` and
`SeedResult` directly through the Server point HTTP API.

Handlers are installed once when an Adapter instance is assembled. The
dispatcher has no runtime registration, discovery, fallback, or generic JSON
payload. The first implementation installs only the Task Item result handler.
Trusted Adapter-generated `3001` evidence bypasses Worker message handling and
enters the Adapter's pending result path directly.

## Dispatch

Each Adapter has one mailbox cursor and never runs concurrent HSCAN rounds:

```text
flush pending results
-> drain one bounded Worker result batch
-> consume one command page through Server batch HTTP
-> filter expired commands
-> deliver different Workers with bounded parallelism
-> append trusted Adapter rejections
```

Delivery evidence remains:

```text
DELIVERED
  command entered the WebSocket send path

REJECTED_BEFORE_SEND
  command was confirmed not to enter Worker delivery
  -> generate 3001

UNKNOWN
  send started but its outcome is ambiguous
  -> generate no 3xxx evidence
```

The result buffer and pending retry state are process-local and bounded.
Shutdown stops new rounds, closes the listener and active connections, and
drains all accepted result batches. Process failure can still lose in-memory
evidence; Item claims and Worker lease fences remain the Kernel convergence
boundary.

## Netty Transport

Each instance owns an embedded Netty listener with one I/O event-loop thread.
Command delivery concurrency is controlled separately by
`deliveryParallelism`. The WebSocket handler only resolves `workerId`, decodes
text frames, invokes the static dispatcher, and maps handling results to
Channel actions. The connection registry stores the current Netty Channel
directly. Cursor, Gateway calls, `3001`, pending result retry, and `UNKNOWN`
semantics stay in the Adapter core; outcome validation stays in the installed
message handler; queue mechanics stay in the bounded result buffer.

The JDK `HttpClient` Gateway implementation also lives in this module. Its
private HTTP DTOs match the Server batch API but are not part of the shared
Worker protocol.

## Verification

```text
./gradlew :worker_delivery_adapter_jvm:test
```
