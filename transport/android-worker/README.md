# XA Mass Android Worker

`transport:android-worker` is the Android WebSocket Worker assembly. It
depends on `transport:worker-core` and OkHttp, but not on
`transport:java-worker`.

It owns `AndroidWorker`, Application-scoped Host Resources, Android identity
storage, Android Register/Bind and WebSocket Clients, and Application Context
adaptation. Core owns Preparation, lifecycle coordination, text Worker
protocol, and synchronous Definition dispatch.

Android does not implement a second Worker lifecycle, persist Endpoint URIs,
or cache Worker Commands or Results.

## Assembly

```java
AndroidWorkerHostResources resources =
        AndroidWorkerHostResources.create(
                1,
                "xa-android-worker"
        );

AndroidWorker worker = AndroidWorker.builder(
                applicationContext,
                URI.create("http://127.0.0.1:18082"),
                "android-demo-workers"
        )
        .hostResources(resources)
        .workerProperties(context -> Map.of(
                "runtime", "android",
                "packageName", context.getPackageName()
        ))
        .eventDefinitions(definitions)
        .requestTimeout(Duration.ofSeconds(10))
        .reconnectPolicy(TextMessageReconnectPolicy.defaults())
        .build();

worker.addListener(snapshot -> observe(snapshot));
worker.start();
```

The Builder performs no file or network access. Android generates and stores a
canonical UUID client key with the platform-issued Worker ID under the
application package and WorkerGroup coordinate. A valid Worker ID skips
Register on later starts. The Properties function cannot override the
reserved `clientWorkerKey` field.

Only one active `AndroidWorker` for a package and WorkerGroup is allowed in
one process. An `Application`, Service, or another Host owner decides its
lifetime; Activity lifecycle is not part of the Worker contract.

## Host Resources

`AndroidWorkerHostResources.create(workerCapacity, threadNamePrefix)` owns:

- one shared OkHttp Dispatcher and ConnectionPool;
- one shared network `HandlerThread` and Looper;
- one bounded Control executor.

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

The single-Worker demo budget is one network HandlerThread and one Control
thread, plus OkHttp's shared internal threads. A per-Client Handler is not a
thread.

## Lifecycle

```text
start on a Host-selected background thread
  -> load one complete Properties snapshot
  -> recover Worker ID, or Register and persist it
  -> always Bind
  -> install one Core TextMessageWorkerTransport
  -> return while WebSocket connection proceeds asynchronously

temporary disconnect
  -> reconnect to the current URI within the Client budget
  -> no Register or Bind

endpoint retry exhausted
  -> stop accepting Commands
  -> finish the current Handler and discard its late Result
  -> enter STOPPED
  -> wait for an explicit Host start
```

Register or Bind failure ends that single start attempt. `start()` is
synchronous through Preparation and must not run on the Main Looper. UI Hosts
normally submit it to `resources.controlExecutor()`. A stop may wait for the
current protocol callback, so UI Hosts schedule it by the same rule.

`WorkerLifecycle` exposes only `STOPPED / RUNNING`. Physical WebSocket state
and reconnect attempts are private Client state, not Adapter, Kernel, or
scheduling truth. Listener calls are synchronous and are not moved to the Main
Looper automatically.

Applications decide whether Android Backup may migrate Worker Identity. The
repository demo excludes the Android Worker preference file from backup.

## Verification

```text
./gradlew :transport:android-worker:testDebugUnitTest
./gradlew :transport:android-worker:assembleDebug
./gradlew :transport:android-worker:assembleDebugAndroidTest
```
