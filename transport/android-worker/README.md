# XA Mass Android Worker

`transport:android-worker` is the Android WebSocket Worker assembly. It
depends on `transport:worker-core` and OkHttp, but not on
`transport:java-worker`.

It owns:

- `AndroidWorker`, the public Android composition facade.
- `AndroidWorkerHostResources`, the Application-scoped network and execution
  resource owner.
- Android `SharedPreferences` storage for a generated client key and the
  platform-issued long-lived Worker ID.
- Android OkHttp Register/Bind and WebSocket implementations.
- Application-Context-specific Worker Properties loading.

Core owns Register/Bind preparation, the two-state run controller, text Worker
protocol, command dispatch, event definitions, and the immutable reconnect
policy. The Android WebSocket Client owns its current connection attempt,
stable-window accounting, and bounded reconnect state. Android does not
implement a second Worker lifecycle, persist Endpoint URIs, or cache Worker
Commands or Results.

## Assembly

```java
AndroidWorkerHostResources resources =
        AndroidWorkerHostResources.create(
                1,
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

The Builder performs no file or network access. It accepts no Worker ID,
Endpoint URI, endpoint manager ID, or caller-supplied client key. Android
generates a canonical UUID client key and stores it with the platform-issued
Worker ID under the application package and WorkerGroup coordinate. A valid
stored Worker ID skips Register on later starts. The Properties function may
not override the reserved `clientWorkerKey` field.

Only one active `AndroidWorker` for a package and WorkerGroup is allowed in one
process. `AndroidWorker` is independent of Activity lifecycle; an
`Application`, Service, or another Host owner decides when to invoke it.

## Host Resources

`AndroidWorkerHostResources.create(workerCapacity,
maxConcurrentCommands, threadNamePrefix)` creates one process-level bundle:

- one shared OkHttp Dispatcher and ConnectionPool;
- one shared network `HandlerThread` and Looper;
- one bounded Control executor;
- one fixed, zero-buffer Command executor.

The single-Worker example uses one Command slot, so its application-owned
budget is one network thread, one Control thread, and at most one Command
thread, plus OkHttp's shared internal network resources. A per-Client
`Handler` borrows the shared network Looper and is not another thread.

Each WebSocket Client owns a separate `Handler` bound to the shared network
Looper. That Handler serializes connection state, current-attempt filtering,
stable-window accounting, and reconnect timers. A superseded physical
connection is rejected by `ConnectionAttempt` object identity even when it
used the same Endpoint URI. OkHttp callbacks only post network events there.
Business Handlers run only in the Command pool, so a slow capability does not
block WebSocket callbacks or reconnect.

The Command pool uses `SynchronousQueue`. If all
`maxConcurrentCommands` slots are occupied, the new Command immediately gets
a correlated `1500` and is never queued. Definitions and Handlers must be
thread-safe when concurrency is greater than one.

Closing one Worker or Client does not quit the shared Looper, close shared
OkHttp infrastructure, or affect another Worker. The Host must close every
Worker before closing `AndroidWorkerHostResources`.

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
  -> wait for accepted Handlers and discard late Results
  -> enter STOPPED
  -> wait for an explicit Host start
```

Register or Bind failure ends that single start attempt. `start()` is
synchronous through Preparation and must not run on the Main Looper. UI Hosts
normally submit it to `resources.controlExecutor()`. Core does not install a
retry or restart scheduler.

`WorkerLifecycle` exposes only `STOPPED / RUNNING`. Physical WebSocket state
and reconnect attempts are private Client state, not Adapter, Kernel, or
scheduling truth. Listener calls are synchronous level observations and are
not moved to the Main Looper automatically.

Applications must decide whether Android Backup may migrate Worker Identity.
The repository demo excludes the Android Worker preference file from backup.

## Verification

```text
./gradlew :transport:android-worker:testDebugUnitTest
./gradlew :transport:android-worker:assembleDebug
./gradlew :transport:android-worker:assembleDebugAndroidTest
```
