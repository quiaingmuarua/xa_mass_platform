# Android Capability HTTP

`:xa-android:capability-http` is a device-local Lab probe for Android Worker
capabilities. It exposes the same immutable `TASK` Definitions used by an
Android Worker through HTTP bound only to `127.0.0.1` on the port selected by
the Host. The Worker Demo currently passes port `18084`.

The probe proves that a capability's parameter resolver, handler, and result
run inside the installed App process. It does not prove Worker registration,
binding, Adapter delivery, Task scheduling, or Result routing. Use the public
WorkerGroup RPC flow for that end-to-end proof.

## Contract

```text
GET  /health
GET  /events
POST /events/{eventCode}:call
```

Calls require an `application/json` object body. Successful handler JSON is
returned as logical JSON together with the original Worker `outcomeCode`.
Worker input, missing-event, execution, and result errors retain their Worker
codes while using HTTP 400, 404, or 500 respectively.

The public `AndroidCapabilityHttpServer` facade hides NanoHTTPD and owns its
small listener lifecycle. Callers construct it with
`AndroidCapabilityHttpServer.create(port, definitions)`; port `0` requests an
ephemeral OS-assigned port and is useful for tests. The module depends only on
Worker Core and NanoHTTPD; it owns no default port, Worker identity, network
Client, Task, or scheduling state.

## Verification

```powershell
.\gradlew.bat :xa-android:capability-http:testDebugUnitTest
.\gradlew.bat :xa-android:capability-http:assembleDebug
```
