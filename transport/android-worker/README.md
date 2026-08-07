# XA Mass Android Worker

`transport:android-worker` is the Android WebSocket Worker assembly. It
depends on `transport:worker-core` and OkHttp, but not on
`transport:java-worker`.

It owns:

- `AndroidWorker`, the public Android composition facade.
- Android `SharedPreferences` storage for a generated client key and the
  platform-issued long-lived Worker ID.
- Android OkHttp Register/Bind Client and HandlerThread WebSocket Client.
- Application-Context-specific Worker Properties loading.

Core owns the shared `TextMessageWorkerRuntime`, command dispatcher, event
definitions, `TextMessageWorkerTransport`, pending result behavior, and startup
state machine. Android does not implement a second Worker lifecycle or persist
Endpoint URIs.

`AndroidWorker` implements Core's `WorkerLifecycle`. Its state, snapshot, and
listener types are the shared Core contract rather than Android mirrors. The
Android facade remains final and owns only Android composition concerns such
as Application Context, SharedPreferences Identity, and the process-local
single-instance guard.

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

The first start generates and synchronously persists a canonical UUID
`clientWorkerKey`. Android injects it as the reserved
`workerProperties.clientWorkerKey`; the Properties function may not override
it. Register returns the platform Worker ID, which is stored separately. A
valid stored Worker ID skips Register on later starts.

The Builder never accepts `workerId`, `endpointManagerId`, an Endpoint URI, or
a caller-supplied client key. `build()` performs no file or network access.
Identity is scoped by application package and WorkerGroup, and only one active
`AndroidWorker` for that coordinate is allowed in one process.

## Lifecycle

```text
start
  -> load one complete Properties snapshot with Application Context
  -> restore Worker ID, or Register and persist it
  -> always Bind
  -> connect Core WebSocket Transport to the returned URI

temporary disconnect
  -> Android WebSocket Client reconnects to the current session URI
  -> no Register or Bind

stop
  -> close the current session
  -> retain client key and Worker ID

refreshProperties
  -> re-read the complete snapshot
  -> Bind only when changed
```

`start`, `stop`, and `close` are idempotent at their lifecycle boundaries;
`close` is terminal. Listener callbacks run on Core's serialized Worker
lifecycle thread, not the main Looper. The module installs no Activity,
Service, WorkManager, or process survival policy. An `Application`, `Service`,
or another host owner decides when to invoke the lifecycle.

Closing the Android network Client marks it terminal and cancels the current
socket before returning; HandlerThread cleanup is posted asynchronously, so a
host lifecycle callback does not wait for a network timeout.

Shared `WorkerLifecycle` observation states are:

```text
STOPPED
STARTING
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

Applications must decide whether Android Backup may migrate Worker Identity.
The repository demo excludes the Android Worker preference file from backup.

```text
./gradlew :transport:android-worker:testDebugUnitTest
./gradlew :transport:android-worker:assembleDebug
./gradlew :transport:android-worker:assembleDebugAndroidTest
```
