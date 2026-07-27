# XA Mass JVM Worker Delivery Adapter Core

Status: framework-free Java 21 Adapter mechanism.

This module owns the transport-independent Adapter behavior:

```text
Server Adapter batch HTTP API
  -> WorkerDeliveryGatewayClient
  -> WorkerDeliveryAdapter.dispatchOnce
  -> WorkerSessionDirectory
  -> WorkerConnection
  -> bounded Worker/Adapter result buffering
  -> Server Adapter batch result API
```

It has no Spring, Spring Boot, WebSocket, Server, Kernel, Redis, scheduling,
score, thread, or lifecycle dependency.

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

## HTTP Client And Host Boundary

The JDK `HttpClient` Gateway implementation remains in this module. Its JSON
DTOs are private and do not enter the shared Worker protocol.

The Core has no executable Main and no concrete WebSocket transport host.
`server_jvm` exposes the point/batch Worker Delivery HTTP API but does not
start or embed this Adapter.

A future host supplies `WorkerConnection` implementations, converts transport
connect/result/disconnect events into Core calls, and schedules
`dispatchOnce()`. That host is a deployment decision and must not move cursor,
session generation, result buffering, `3001`, or `UNKNOWN` semantics out of
this module.

## Verification

```text
./gradlew :worker_delivery_adapter_jvm:test
```
