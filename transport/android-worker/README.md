# XA Mass Android Worker

`transport:android-worker` is the Android WebSocket Worker assembly. It
depends on `transport:worker-core` and OkHttp, but not on
`transport:java-worker`.

It owns:

- `AndroidWorker`, the public lifecycle and composition entry.
- Android `SharedPreferences` storage for long-lived Worker Identity and the
  Endpoint cache.
- Android OkHttp Register/Bind and HandlerThread WebSocket Clients.
- Properties snapshot validation/fingerprinting and cached-route recovery.

It reuses Core's `WebSocketWorkerTransport`, command dispatcher, event
definitions, connection Bind frame, pending result behavior, and reconnect
protocol. It does not implement a second command or result state machine.

## Assembly

```java
AndroidWorker worker = AndroidWorker.builder(
                applicationContext,
                URI.create("http://127.0.0.1:18082"),
                "android-demo-workers"
        )
        .workerProperties(context -> Map.of(
                "runtime", "android",
                "packageName", context.getPackageName()
        ))
        .eventDefinitions(definitions)
        .requestTimeout(Duration.ofSeconds(10))
        .reconnectInterval(Duration.ofMillis(250))
        .build();

worker.addListener(snapshot -> observe(snapshot));
worker.start();
```

`clientWorkerKey(...)` is optional. When omitted, the first start generates a
canonical UUID and persists it. A configured key must match any persisted key.
The Builder never accepts `workerId`, `endpointManagerId`, or an Endpoint URI,
and `build()` performs no file or network access.

The host chooses the process owner. An `Application`, `Service`, or another
component may retain the Worker and call:

```text
start             asynchronous and idempotent
stop              release network resources, retain Identity and cache
refreshProperties re-read the complete snapshot and Bind only if changed
snapshot          current local lifecycle observation
close             terminal idempotent close
```

Listener callbacks run on the Worker's serialized lifecycle thread, not the
main Looper. The module installs no Activity, Service, WorkManager, or process
survival policy.

## Identity And Endpoint

Identity is scoped by application package and WorkerGroup:

```text
workerGroupId
clientWorkerKey
workerId
```

An existing valid `workerId` skips Register. A missing ID uses the persisted,
configured, or newly generated client key and calls Register once. Corrupt or
conflicting identity fails closed; `stop()` and `close()` never delete it.
Only one active `AndroidWorker` per application and WorkerGroup is allowed in
one process.

Each start obtains a complete JSON-compatible Properties snapshot using the
Application Context. The Worker recursively copies it, computes a canonical
SHA-256, and compares it with the Endpoint cache:

```text
cache absent, corrupt, or for another identity -> Bind
Properties digest changed                     -> Bind
valid matching cache                          -> connect directly
```

The cache stores only WorkerGroup, Worker ID, Endpoint URI, and Properties
digest. It is an optimization, not Binding truth or authentication. A cache
write failure does not prevent connecting to the URI returned by the current
Bind. Properties and Property Index updates remain independent.

When a cached URI fails to remain connected for one request-timeout window
three consecutive times, one Bind refresh is allowed for that `start()`
cycle. The old Client continues reconnecting while refresh runs. A new URI
replaces the Core Transport; an unchanged URI only refreshes the cache. A
failed refresh retains the old route and does not form a Bind loop.

Applications must decide whether Android Backup may migrate Worker Identity.
The repository demo explicitly excludes the Android Worker preference file
from backup.

## State

Local state is:

```text
STOPPED
REGISTERING
BINDING
CONNECTING
TRANSPORT_CONNECTED
ERROR
CLOSED
```

`TRANSPORT_CONNECTED` means the WebSocket opened and Core handed the
workerId-only connection Bind to the network stack. It does not assert Adapter
route verification, Kernel online truth, or assignment availability.

## Verification

```text
./gradlew :transport:android-worker:testDebugUnitTest
./gradlew :transport:android-worker:assembleDebug
./gradlew :transport:android-worker:assembleDebugAndroidTest
```
