# XA Mass JVM Worker Delivery Adapter

Status: embeddable and standalone Java 21 WebSocket Adapter.

This module owns transport behavior only:

```text
Server Adapter batch HTTP API
  -> WorkerDeliveryGatewayClient
  -> bounded mailbox pump
  -> process-local WebSocket sessions
  -> WorkerCommand push
  -> Worker/Adapter results
  -> Server Adapter batch result API
```

It does not own WorkerCommand or SeedResult Redis truth. It has no dependency
on `server_jvm`, `kernel_jvm`, Redis, Task scheduling, scores, or Pacers.
Embedded mode deliberately uses HTTP loopback; there is no in-process fast
path.

## WebSocket Contract

Workers connect to:

```text
GET /api/v1/worker-delivery/websocket/workers/{workerId}
```

Server-to-Worker text frames are exact `WorkerCommandEnvelope` JSON.
Worker-to-Server frames are exact `SeedResult` JSON limited to `200/1xxx`.
Binary frames, malformed JSON, and Worker-originated `3xxx` close the session.

One Adapter instance owns one non-`system-polling` endpoint-manager identity.
The pump cursor-consumes that identity's Server mailbox through HTTP. A command
for which no session exists produces trusted `3001` evidence. An ambiguous
failure after send begins remains `UNKNOWN` and does not produce `3xxx`.

Results are retained only in a bounded process-memory buffer. Failed Server
submissions are retried before more commands are consumed. Process failure may
lose buffered results; the Kernel lease fences remain the convergence
boundary.

## Configuration

```yaml
xa:
  mass:
    worker-delivery:
      adapter:
        websocket:
          enabled: true
          endpoint-manager-id: websocket-adapter-1
          gateway-base-url: http://127.0.0.1:18082
          request-timeout: 5s
          pump-interval: 100ms
          scan-count: 100
          result-batch-size: 100
          result-buffer-capacity: 1000
          send-time-limit: 5s
```

The endpoint-manager ID must be nonblank and cannot be `system-polling`.
The Gateway URL must be an absolute HTTP or HTTPS URL. All durations and
bounds must be positive.

## Deployment

Embedded mode is enabled through `server_jvm`; it still calls the Server's
batch HTTP routes. The Server defaults this mode to disabled.

Standalone mode listens on port `18083` by default:

```text
./gradlew :worker_delivery_adapter_jvm:bootRun --args="--xa.mass.worker-delivery.adapter.websocket.endpoint-manager-id=websocket-adapter-1"
```

Its default Gateway is `http://127.0.0.1:18082`. Standalone exposes only the
WebSocket endpoint and health probes; it does not expose Worker point/batch
Gateway routes, Kernel resource APIs, or Redis health.

Do not run embedded and standalone Adapters for the same endpoint-manager ID.
The first version has no distributed ownership lease.

## Verification

```text
./gradlew :worker_delivery_adapter_jvm:test
```
