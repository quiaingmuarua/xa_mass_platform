# XA Mass JVM Worker Delivery Adapter

Status: Java 21 Adapter mechanism with a framework-free Core and a concrete
Spring WebSocket transport.

The module's Core owns the transport-independent Adapter behavior:

```text
Server Adapter batch HTTP API
  -> WorkerDeliveryGatewayClient
  -> WorkerDeliveryAdapter.dispatchOnce
  -> WorkerSessionDirectory
  -> WorkerConnection
  -> bounded Worker/Adapter result buffering
  -> Server Adapter batch result API
```

The `application` and `http` packages have no Spring, Spring Boot, Server,
Kernel, Redis, scheduling, score, thread, or lifecycle dependency.

## Stable Core

The stable boundaries are:

```text
WorkerDeliveryGatewayClient
  consume one bounded command page and append one result batch

WorkerConnection
  attempt one already-assigned command and close one transport connection

WorkerSessionDirectory
  issue generation tokens, replace sessions, deliver by WorkerId, and close

WorkerDeliveryAdapter
  accept current-session results and execute one bounded dispatch round
```

`WorkerSessionToken` exposes `workerId + generation`, while each Directory
implementation privately creates its token. A newer binding replaces the
previous connection; stale disconnect callbacks and stale results cannot act
as the current generation.

Delivery evidence is explicit:

```text
DELIVERED
  transport accepted the command

REJECTED_BEFORE_SEND
  command was confirmed not to have entered Worker delivery
  -> Core may generate 3001

UNKNOWN
  send started or the outcome is ambiguous
  -> no 3xxx evidence
```

The Core keeps the mailbox cursor, bounded result buffer, one pending result
batch, deadline filtering, and trusted `3001` construction. Pending results are
retried before consuming more commands. Process failure may lose in-memory
results; Kernel Item claims and Worker lease fences remain the convergence
boundary.

`InMemoryWorkerSessionDirectory` is the current process-local implementation.
Different Adapter instances may own different endpoint-manager IDs. The Core
does not provide same-endpoint distributed ownership.

## HTTP Client And WebSocket Transport

The JDK `HttpClient` Gateway implementation remains in this module. Its JSON
DTOs are private and do not enter the shared Worker protocol.

The module also implements the concrete WebSocket transport:

```text
WorkerWebSocketHandler
  frame and connection event adaptation

SpringWebSocketWorkerConnection
  WorkerCommand text-frame delivery

WorkerWebSocketEndpointConfigurer
  /api/v1/worker-delivery/websocket/workers/{workerId}
```

This dependency is Spring Framework WebSocket, not Spring Boot. The module has
no application Main, configuration properties, scheduled thread, or framework
lifecycle. `server_jvm` supplies those process concerns and may enable this
Adapter. The Server host must not move cursor, session generation, result
buffering, `3001`, or `UNKNOWN` semantics out of the Core.

## Verification

```text
./gradlew :worker_delivery_adapter_jvm:test
```
