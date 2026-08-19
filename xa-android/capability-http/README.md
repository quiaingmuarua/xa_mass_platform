# Android Capability HTTP

`:xa-android:capability-http` is a device-local Lab probe for immutable Android
extension Definitions. It exposes the Host-supplied finite collection through
HTTP bound only to `127.0.0.1` on the selected port. The Worker Demo passes
port `18084`, the same three business Definitions used by its Android Worker,
and three additional local-only Host lifecycle Definitions.

The probe proves that a Definition's parameter resolver, handler, and result
run inside the installed App process. A Host may use the same dispatcher for
its own finite local control Definitions, but the HTTP module does not add or
register them dynamically. Local calls alone do not prove Worker registration,
binding, Adapter delivery, Task scheduling, or Result routing. The Worker Demo
Emulator lane closes those relations through public Runtime APIs.

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
Client, Task, or scheduling state. It accepts extension Definitions only and
exposes no dynamic registration surface or second HTTP control route.

## Verification

```powershell
.\gradlew.bat :xa-android:capability-http:testDebugUnitTest
.\gradlew.bat :xa-android:capability-http:assembleDebug
```
