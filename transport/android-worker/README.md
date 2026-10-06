# XA Mass Android Worker

`transport:android-worker` is the Android WebSocket Worker assembly. It
depends on `transport:worker-core` and OkHttp, but not on
`transport:java-worker`.

The shared Core [reporting Handler overload](../worker-core/README.md#later-task-outcome-observations)
also supports Android business callbacks after send execution completes. Its
Reporter uses its original run's WebSocket and becomes unavailable after stop;
Android adds no observation queue, thread or retry.

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

## Proactive Properties

`AndroidWorker.reportProperties()` and `reportProperties(updates)` use the
Application-context Provider supplied at construction and the current run's
WebSocket. The [Core Properties reporting contract](../worker-core/README.md#properties-reporting)
owns Provider reads, argument/failure behavior, local acceptance and run
revocation; the [event catalog](../EVENTS.md#worker-produced-property-observation)
owns payload semantics. Android borrows its existing Client and adds no
Properties HandlerThread, publication scheduler or stored snapshot.

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
physical connection even when reconnect uses the same Endpoint URI. The current
callback gate also affects teardown, as recorded in the
[known stop difference](#known-android-stop-difference). Closing one Client
does not quit the shared Looper or close shared OkHttp resources.

The single-Worker budget is one network HandlerThread and one Control thread,
plus OkHttp's internal threads. A per-Client Handler is not a thread.

## Lifecycle

### Start

`start()` submits one Preparation to the Control executor and returns without
waiting for it; the Host may call it from the Main Looper. Preparation failure
ends that start attempt. The accepted run follows:

```text
queued startup request
  -> load and defensively copy one complete Properties map
  -> one Prepare request resolves Worker ID and Endpoint
  -> install one Core TextMessageWorkerTransport
  -> concrete Client connects asynchronously

temporary disconnect
  -> reconnect to the current URI within the Client budget
  -> no Prepare

endpoint retry exhausted
  -> stop accepting Commands
  -> finish the current Handler and discard its late Result
  -> enter STOPPED
  -> wait for an explicit Host start
```

Prepare uses the existing input only for Server-owned identity and access
preparation; Properties publication follows the
[Core reporting path](../worker-core/README.md#properties-reporting).

### Stop

During Preparation, `stop()` marks its eventual result for discard and keeps
that one call single-flight until it returns. With an active Transport, it
commits `STOPPED` and detaches the run before closing the Client outside the
Core state gate. The target is for active stop to return without waiting for
an admitted Handler. The [known Android stop difference](#known-android-stop-difference)
describes why the current caller may still wait during Client teardown.

### Close

`close()` is terminal and synchronous: it revokes the run, closes Preparation
and the Client, then releases the Platform. Current Client teardown may wait
for an admitted protocol callback. Closing the object does not preserve
Commands, Results or Reporters for another Worker.

`WorkerLifecycle` exposes only `STOPPED / RUNNING`. Physical WebSocket state
and reconnect attempts are private Client state, not Adapter, Kernel, or
scheduling truth. Listener calls are synchronous and are not moved to the Main
Looper automatically.

Applications decide whether Android Backup may migrate the stable client key.
The repository demo excludes the Android Worker preference file from backup.

## Known Android Stop Difference

The shared target remains non-blocking active stop: revoke the run before
Client teardown and let already-admitted Handlers finish independently. Android
currently differs from that target. `AndroidWorker.stop()` calls Core directly;
Core commits `STOPPED` and detaches the Transport, then calls Client close on
the caller's thread. `AndroidOkHttpTextWebSocketClient.close()` marks the Client
closed and cancels its socket before waiting for `callbackGate`. The same gate
covers the complete synchronous Transport callback, including its Handler.

Consequently `snapshot()` can already report `STOPPED` while the `stop()` caller
and its subsequent lifecycle notification wait for that Handler. There is no
callback-wait timeout. A Handler can close its own Client reentrantly. This
known implementation gap must remain visible until the Android lifecycle is
reconciled with the shared target; callers must not infer a non-blocking return
from the committed run state. Blocking or UI-sensitive Host code must account
for the current wait.

The evidence is the [Core stop order](../worker-core/src/main/java/com/xa/mass/worker/runtime/WorkerRunController.java),
the [Android Client callback and close gates](src/main/java/com/xa/mass/worker/android/AndroidOkHttpTextWebSocketClient.java),
and `externalCloseWaitsForCurrentCallback` in the
[Client Owner test](src/test/java/com/xa/mass/worker/android/AndroidOkHttpTextWebSocketClientTest.java).
Those establish the source contract and test expectation; they do not establish
device-specific blocking symptoms.

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

## Report Event Semantics

The shared Core emits `platform.worker.command.succeeded` or
`platform.worker.command.failed`, preserving opaque Handler output and the
Command forward. `diagnosticCode` is diagnostic only (empty for success).
Properties and identity use their distinct Report names, not callable Handler
entries. See the [Delivery contract](../worker-delivery-contract/README.md#report-semantics)
for coordinated Server, Adapter, SDK and client upgrade requirements.
