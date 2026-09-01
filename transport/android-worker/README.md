# XA Mass Android Worker

`transport:android-worker` is the Android WebSocket Worker assembly. It
depends on `transport:worker-core` and OkHttp, but not on
`transport:java-worker`.

It owns `AndroidWorker`, its package-private Platform resources, persistent
WorkerGroup/client key coordinates, Android Prepare and WebSocket Clients, and
Application Context adaptation. Core owns Preparation, lifecycle coordination, text
Worker protocol, and synchronous Definition dispatch.

Android does not implement a second Worker lifecycle, persist Endpoint URIs,
or cache Worker Commands or Results.

## Assembly

```java
AndroidWorker worker = AndroidWorker.create(
        applicationContext,
        URI.create("http://127.0.0.1:18082"),
        "android-demo-workers",
        context -> Map.of(
                "runtime", "android",
                "packageName", context.getPackageName()
        ),
        definitionExtensions,
        WorkerConnectionOptions.defaults()
);

worker.addListener(snapshot -> observe(snapshot));
worker.start();
```

`create()` builds the complete Worker and local Platform resources but does
not load the client key, call Prepare, or connect. The common overloads omit
extensions and/or use default connection options. Android generates and stores
a canonical UUID client key together with its WorkerGroup coordinate. It never
stores the platform-issued Worker ID; any legacy preference value for that ID
is ignored. The Properties function cannot override the reserved
`clientWorkerKey` field. Its ordinary Prepare request explicitly selects the
Server `CLIENT_KEY` registration policy; it does not use Scenario Lab inventory
coordinates or the optional batch endpoint.

The supplied Definitions are business extensions, not a complete Handler map.
Assembly delegates to Core's static Definition assembly, which adds
`platform.worker.probe`, `platform.worker.properties.snapshot` and
`platform.worker.events.snapshot` before the defensive copy and rejects
duplicate full Event Names. Host code registers only short capability names
through `WorkerEventDefinition.extension(...)`; the internal map uses their
`extension.worker.*` Event Names. Properties remain live, while the sorted
Event snapshot is fixed for the Worker process lifetime. Neither exposes the
assembly-owned `clientWorkerKey`. Connection close is handled by Transport
rather than registered as a Definition.

Only one active `AndroidWorker` for a package and WorkerGroup is allowed in
one process. An `Application`, Service, or another Host owner decides its
lifetime; Activity lifecycle is not part of the Worker contract.

## Platform Resources

Each `AndroidWorker` internally owns:

- one shared OkHttp Dispatcher and ConnectionPool;
- one shared network `HandlerThread` and Looper;
- one single-thread Control executor.

There is no Command executor. Each WebSocket Client has a lightweight Handler
bound to the shared Looper, used only for connection creation, stable-window
checks, and reconnect timers. OkHttp protocol callbacks pass through a
per-Client serialization gate and invoke the Core Transport and business
Handler synchronously on the OkHttp callback thread.

Consequently one connection processes Commands serially, while separate
Worker connections can run concurrently on OkHttp. A slow Handler applies
natural backpressure only to its connection and does not occupy the shared
reconnect HandlerThread. Definitions shared by Worker instances must still be
thread-safe.

`ConnectionAttempt` object identity suppresses callbacks from a superseded
physical connection even when reconnect uses the same Endpoint URI. External
Client close waits for the current callback; callback code may close its own
Client reentrantly. Closing one Client does not quit the shared Looper or close
shared OkHttp resources.

The single-Worker budget is one network HandlerThread and one Control thread,
plus OkHttp's internal threads. A per-Client Handler is not a thread.

## Lifecycle

```text
start from any Host thread, including the Main Looper
  -> submit one startup request to the internal Control executor
  -> load and defensively copy one complete Properties map
  -> one Prepare request resolves Worker ID and Endpoint
  -> install one Core TextMessageWorkerTransport
  -> return while WebSocket connection proceeds asynchronously

temporary disconnect
  -> reconnect to the current URI within the Client budget
  -> no Prepare

endpoint retry exhausted
  -> stop accepting Commands
  -> finish the current Handler and discard its late Result
  -> enter STOPPED
  -> wait for an explicit Host start
```

Prepare is the only canonical Properties refresh. A running
provider change can be read through an explicit Worker snapshot Command, but
it is not published and reaches Kernel resource truth only after the next
explicit stop/start.

Prepare failure ends that single start attempt. `start()` and
`stop()` return after submitting their request, so Android hosts do not need a
lifecycle Executor wrapper. `close()` is synchronous and may wait for the
current protocol callback before it releases the Controller and Platform.

`WorkerLifecycle` exposes only `STOPPED / RUNNING`. Physical WebSocket state
and reconnect attempts are private Client state, not Adapter, Kernel, or
scheduling truth. Listener calls are synchronous and are not moved to the Main
Looper automatically.

Applications decide whether Android Backup may migrate the stable client key.
The repository demo excludes the Android Worker preference file from backup.

## Verification

```text
./gradlew :transport:android-worker:testDebugUnitTest
./gradlew :transport:android-worker:assembleDebug
./gradlew :integrations:android-worker-proof:test
```

There is no instrumentation or UI-automation source set. The path-selected
`Android Worker Proof` belongs to `:integrations:android-worker-proof` and uses
the Debug Demo plus three fixed application-ID Lab variants to prove one-Worker
lifecycle behavior and same-Group process isolation through a real API 33
emulator, Server, Adapter, Kernel projection, and managed Task Call.
Real-device runs remain the manual proof for vendor systems, physical Battery
behavior, and background execution limits.
