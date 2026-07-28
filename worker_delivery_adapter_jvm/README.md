# XA Mass JVM Worker Delivery Adapter

Status: Java 21 Adapter Runtime with a transport-independent one-round Core,
local lifecycle, and a concrete Spring WebSocket Adapter type.

The module owns the complete local Adapter lifecycle:

```text
AdapterDefinition
  -> WorkerDeliveryAdapterManager.register
  -> WorkerDeliveryAdapter.start
  -> scheduled WorkerDeliveryAdapterCore dispatch rounds
  -> WorkerDeliveryAdapter.close
```

Registration is process-local composition. It does not publish Adapter
lifecycle truth to the Kernel or Server. One JVM currently registers at most
one active Adapter instance and the only implemented type is `WEBSOCKET`.

## Stable Runtime

The stable boundaries are:

```text
WorkerDeliveryAdapterDefinition
  AdapterType + common runtime config + type-private config

WorkerDeliveryAdapterManager
  register one local definition, start it, and close it

WorkerDeliveryAdapter
  expose REGISTERED/RUNNING/STOPPING/CLOSED lifecycle

WorkerDeliveryAdapterCore
  execute one bounded dispatch/result round

WorkerDeliveryGatewayClient
  consume one bounded command page and append one result batch

WorkerConnection
  attempt one already-assigned command and close one transport connection

WorkerConnectionRegistry
  retain one current connection per WorkerId, replace, deliver, and close
```

Common runtime configuration owns the endpoint-manager identity, Gateway HTTP
access, dispatch interval, scan bound, and result bounds. WebSocket
`sendTimeLimit` remains type-private. `system-polling` is a point HTTP binding,
not an active Adapter type.

A newer binding immediately becomes the only command-delivery connection.
Unbind uses `workerId + WorkerConnection` identity, so an old connection's
delayed close callback cannot remove its replacement. The replaced connection
is closed best-effort for resource cleanup.

SeedResult acceptance is deliberately independent of the current connection.
A result already produced through a replaced connection remains valid evidence
for Kernel ResultRouting; opaque result context and Worker lease fences, not
Adapter connection identity, decide whether it can affect current truth.

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

The one-round Core keeps the mailbox cursor, bounded result buffer, one pending result
batch, deadline filtering, and trusted `3001` construction. Pending results are
retried before consuming more commands. Process failure may lose in-memory
results; Kernel Item claims and Worker lease fences remain the convergence
boundary.

`InMemoryWorkerConnectionRegistry` is the current process-local implementation.
Different JVM processes may own different endpoint-manager IDs. The runtime
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

`WebSocketWorkerDeliveryAdapterFactory` constructs the Gateway HTTP client,
Core, connection registry, scheduled runtime, and handler. This module owns the
dispatch scheduler but depends only on Spring Framework WebSocket, not Spring
Boot. It has no application Main, Server implementation, Kernel runtime, or
Redis dependency.

`server_jvm` binds external configuration, installs the WebSocket endpoint, and
maps process-ready/process-close events to `manager.start()` and
`manager.close()`. It does not call `dispatchOnce` or own cursor,
active-connection selection, result buffering, `3001`, or `UNKNOWN` semantics.

## Verification

```text
./gradlew :worker_delivery_adapter_jvm:test
```
