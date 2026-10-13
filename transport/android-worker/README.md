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
callback gate serializes admission, as recorded in
[callback admission and close](#callback-admission-and-close). Closing one Client
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
Core state gate. Active stop returns without waiting for an admitted Handler.
The old callback may finish independently; it cannot transfer its Reporter or
send through the closed Client to a new run.

### Close

`close()` is terminal and synchronous: it revokes the run, closes Preparation
and the Client, then releases the Platform. Client teardown does not wait
for an admitted protocol callback. Closing the object does not preserve
Commands, Results or Reporters for another Worker.

`WorkerLifecycle` exposes only `STOPPED / RUNNING`. Physical WebSocket state
and reconnect attempts are private Client state, not Adapter, Kernel, or
scheduling truth. Listener calls are synchronous and are not moved to the Main
Looper automatically.

Applications decide whether Android Backup may migrate the stable client key.
The repository demo excludes the Android Worker preference file from backup.

## Callback Admission And Close

Core commits `STOPPED` and detaches the Transport before closing Client on the
caller's thread. Client close revokes `running`, `currentAttempt` and Listener
together under `stateLock`, removes queued reconnect work and cancels the socket
outside that lock. It never waits for `callbackGate`.

Callbacks retain their serial gate and validate Attempt identity under the
state gate before capturing Listener. A callback admitted before revocation may
finish afterwards; later callbacks cannot be admitted. A Handler may close its
own Client reentrantly. Closing one Client leaves the shared Looper and OkHttp
resources with Platform. No Handler interruption or callback-completion fence
is added, and Host Lifecycle Listeners remain synchronous.

The evidence is the [Core stop order](../worker-core/src/main/java/com/xa/mass/worker/runtime/WorkerRunController.java),
the [Android Client callback and close gates](src/main/java/com/xa/mass/worker/android/AndroidOkHttpTextWebSocketClient.java),
and `externalCloseReturnsWhileCurrentCallbackIsBlocked` in the
[Client Owner test](src/test/java/com/xa/mass/worker/android/AndroidOkHttpTextWebSocketClientTest.java).
Those establish deterministic callback ordering. The Android Emulator Proof
additionally requires stop to return within its five-second HTTP budget while
an admitted thirty-second DELAY remains running; that budget is a proof witness,
not a production latency SLA.

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

Report generation follows the [Core message path](../worker-core/README.md#message-path)
and [Event Catalog](../EVENTS.md#report-event-contracts). Protocol changes require
the [coordinated upgrade](../worker-delivery-contract/README.md#coordinated-upgrade).
