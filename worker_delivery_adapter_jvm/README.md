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
one current WorkerConnection per WorkerId
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

WorkerConnectionRegistry
  retain the current connection for each WorkerId
```

The Manager does not deserialize configuration or construct Adapters. Server
composition converts each configured JSON tree into a concrete instance, then
registers that instance.

A new Worker connection replaces the current connection for its WorkerId.
Unbind compares both `workerId` and connection instance, so a delayed close
from the replaced connection cannot remove the replacement. Results already
produced through an old connection remain valid evidence; Kernel
ResultContext and Worker lease fences decide whether they can affect truth.

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
`deliveryParallelism`. The WebSocket handler only adapts frames and connection
events; cursor, result buffering, `3001`, and `UNKNOWN` semantics stay in the
Adapter core.

The JDK `HttpClient` Gateway implementation also lives in this module. Its
private HTTP DTOs match the Server batch API but are not part of the shared
Worker protocol.

## Verification

```text
./gradlew :worker_delivery_adapter_jvm:test
```
