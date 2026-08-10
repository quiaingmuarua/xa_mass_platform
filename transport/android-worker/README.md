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

Core owns `RegisteredWorkerPreparation`, `WorkerRunController`, command dispatch, event
definitions, and the one-endpoint runtime. The runtime is the final command
admission and performs at most one send for each produced result; the loop
guards one two-state run. Android does not implement a second Worker lifecycle,
persist Endpoint URIs, or cache Worker business messages.

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
        .handlerExecutor(handlerExecutor)
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

`handlerExecutor` is required and Host-owned. `AndroidWorker.close()` does not
shut it down; the Host closes all Workers before shutting down the Executor.
The WebSocket Client's dedicated Android `HandlerThread` remains a separate
connection lane owned and closed by that Client.

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
  -> enter RUNNING on the calling thread
  -> load one complete Properties snapshot with Application Context
  -> restore Worker ID, or Register and persist it
  -> always Bind
  -> install one Core runtime for the returned URI
  -> return while the Client connects asynchronously

temporary disconnect
  -> Android WebSocket Client reconnects to the current URI within its budget
  -> no Register or Bind

endpoint terminated after reconnect exhaustion
  -> the current runtime reports one exit callback
  -> finish any in-flight Handler and discard its result
  -> enter STOPPED and wait for an explicit start

Register or Bind failure
  -> enter STOPPED after that single Preparation attempt
  -> wait for an explicit start

stop
  -> close the Client and reject new commands
  -> wait for any in-flight Handler, discarding its result
  -> enter STOPPED
  -> retain client key and Worker ID
```

`start`, `stop`, and `close` are idempotent at their lifecycle boundaries;
`close` is terminal. Core Listener callbacks are synchronous, lightweight, and
outside its state lock. They may run on a lifecycle caller, a Host control or
Handler executor, or the Android Client callback `HandlerThread`; they are not
main-Looper callbacks. A UI Host must post them to the main Looper. The module
installs no Activity, Service, WorkManager, or process survival policy. An
`Application`, `Service`, or another host owner decides when to invoke the
lifecycle. Because `start()` may block for one Control request timeout, an
Android Host must invoke it away from the Main Looper.

Closing the Android network Client marks it terminal and cancels the current
socket before returning; HandlerThread cleanup is posted asynchronously, so a
host lifecycle callback does not wait for a network timeout.

Shared `WorkerLifecycle` observation has one two-state axis:

```text
State  STOPPED / RUNNING
```

`RUNNING` includes preparation, Client reconnect, command execution, and
graceful stop. Failures return to `STOPPED` with a diagnostic message.
Physical WebSocket state and reconnect attempts are private to the Client and
produce neither lifecycle events nor a connection query. `RUNNING` therefore
does not assert Adapter route verification, Kernel online truth, or assignment
availability. Android uses the default 20-attempt connection budget unless the
Builder receives another immutable `TextMessageReconnectPolicy`. Core does not
retry Register or Bind.

Applications must decide whether Android Backup may migrate Worker Identity.
The repository demo excludes the Android Worker preference file from backup.

```text
./gradlew :transport:android-worker:testDebugUnitTest
./gradlew :transport:android-worker:assembleDebug
./gradlew :transport:android-worker:assembleDebugAndroidTest
```
