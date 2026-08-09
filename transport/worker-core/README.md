# Worker Core

`transport:worker-core` is the Java 11 Worker mechanism shared by Java and
Android assemblies.

It owns:

- Worker event definitions, parameter resolution, dispatch, and outcome
  mapping.
- `WorkerPreparation` and the default Register/Bind implementation.
- `WorkerLoop`, the two-state guard for one Worker run.
- `WorkerExecutionResources`, the non-owning Host injection contract for
  shared control, Handler, and retry execution.
- The package-private one-endpoint text-message runtime.
- Polling Worker behavior and platform-neutral network Client contracts.

It does not own concrete network libraries, Android storage, host process
lifecycle, threads, executors, schedulers, Adapter behavior, Redis, or Kernel
scheduling.

## Execution Resources

Every `WorkerLoop` receives a required `WorkerExecutionResources` bundle:

```text
control ExecutorService          -> preparation and preparation resubmission
handler ExecutorService          -> admitted WorkerEvent Handler execution
retry ScheduledExecutorService   -> bounded preparation retry timers
```

The Host creates these resources, may share one bundle across many Workers,
and closes them only after every consuming Worker is closed. Core never creates
or shuts down an executor. Closing one `WorkerLoop` cancels only its current
prepare/Handler task handles and scheduled retry; it does not affect another
Worker using the same bundle. Prepare and Handler execution remain separate so
a blocked business Handler cannot consume the control path.

## Execution

Every command reaches the same DTO entry:

```text
encoded network input
  -> strict WorkerCommand decode at the transport boundary
  -> TextMessageWorkerRuntime final inbound-command admission
  -> WorkerCommandDispatcher
  -> WorkerEventDefinition(src, eventCode, resolver, handler)
  -> WorkerResult
```

`WorkerCommandDispatcher` does not parse transport data. It checks the
deadline, resolves the static `(src, messageType)` definition, invokes its
resolver and handler, and copies `messageId`, source, message type, and
`forward` into the result.

| Outcome | Meaning |
| --- | --- |
| `200` | Handler returned a non-empty result payload |
| `1400` | Resolver or handler rejected event input |
| `1404` | No definition exists for `(src, messageType)` |
| `1500` | Handler failed or returned an empty payload |

Expired commands are dropped before handler invocation. A deadline is not
rechecked after execution starts.

## One Worker Run

One accepted `WorkerLoop.start()` represents one complete Worker run:

```text
RUNNING
  -> WorkerPreparation with bounded retry
  -> PreparedWorker(workerId, endpointUri)
  -> one reconnecting TextMessageWorkerRuntime
  -> exact-once runtime exit callback
  -> STOPPED
```

Endpoint termination never starts another preparation automatically. A host
may observe `STOPPED` and explicitly call `start()` for a new run.

`RegisteredWorkerPreparation` owns `WorkerIdentityStore`,
`WorkerPropertiesProvider`, and `WorkerControlClient`. Each call reads one
complete immutable Properties snapshot, restores or registers the Worker ID,
persists a newly issued ID, and performs Endpoint Bind. It does not start a
network connection or execute commands.

Java and Android assemblies construct one immutable
`WorkerCommandDispatcher` from their static definitions and inject it as a
`WorkerCommandExecutor`. `WorkerLoop` owns preparation retry, the current
runtime reference, cancellable task handles, and two-state lifecycle
observation. It never inspects command-busy state or routes commands.
Preparation network failures consume the bounded prepare budget. Exhaustion or
a contract failure ends the run in `STOPPED` with diagnostic evidence.

The single-run runtime owns only:

```text
prepared workerId + endpoint Client
  -> onOpen: send WorkerConnectionBind
  -> decode inbound WorkerCommand
  -> final command admission and serial execution
  -> send the resulting WorkerResult at most once
  -> report endpoint termination once
```

It receives only the prepared endpoint and a `WorkerCommandExecutor`. It has
no Identity Store, Properties provider, Control Client, definitions,
dispatcher construction, preparation retry logic, or business-message cache.
Ordinary disconnects, failures, and reconnects stay inside the current
`TextMessageClient` and reuse the prepared endpoint. Only endpoint termination
exits the runtime and ends the Worker run. The current concrete Clients
terminate an endpoint when their reconnect budget is exhausted.

## Command And Result Ownership

`TASK`, `SYSTEM`, and `ADAPTER` commands all enter through the active
connection's inbound text callback. `WorkerLifecycle` exposes no local command
injection or message-send operation, and `WorkerLoop` never accepts or routes a
`WorkerCommand`. The runtime decodes the inbound frame and is the final
admission owner: it accepts only while connected with no command executing. A
malformed or inadmissible inbound frame closes the current connection and is
never queued.

A produced `WorkerResult` is sent once only when the current connection is
still bound. If the Handler finishes while disconnected, send is rejected, or
the runtime is stopping, the result is dropped. It is never replayed after
Client reconnect or a later `start()`. Endpoint termination and graceful
`stop()` wait for an in-flight Handler only to prevent overlapping Worker
runs; they discard its result before issuing the exact-once exit callback.

`PollingWorkerTransport` remains a separate request-response mechanism. It
decodes the point response before calling the same DTO executor and submits a
pending result before polling another command.

## Lifecycle

`WorkerLifecycle` exposes:

```text
start / stop / close
snapshot / isConnected
addListener / removeListener
```

Observation has two independent axes:

```text
State            STOPPED / RUNNING
ConnectionState  DISCONNECTED / CONNECTING / CONNECTED
```

`RUNNING` begins when `start()` is accepted and includes preparation retry,
Client connection/reconnect, command execution, and graceful stop while an
in-flight Handler finishes. `CONNECTED` means the network opened and the
workerId-only connection Bind was accepted by the Client. Failures return to
`STOPPED` and remain available through `diagnosticMessage`; `close()` is an
internal terminal object condition rather than a third Worker state. Neither
axis is Kernel online truth or scheduling availability.
`snapshot()` and `isConnected()` are current queries; lifecycle listeners do
not promise a callback for each transient network disconnect. Listener calls
are synchronous, lightweight, and outside the lifecycle state lock. They may
run on the lifecycle caller, Host control/Handler executor, or Client callback
thread. A no-thread coalescing drain rereads the latest snapshot, preventing a
slow callback from holding the state lock or delivering queued stale
snapshots. Hosts must move UI or blocking observation work onto their own
appropriate executor.

There is intentionally no direct Properties-refresh or local-command lifecycle
method. Platform or Adapter initiated Worker behavior is expressed through
statically installed `WorkerEventDefinition` commands delivered over the
Worker's inbound connection.

## Client Boundary

Core exposes string-only `WorkerPointClient` and `TextMessageClient`, plus the
JDK-type-only `WorkerControlClient`. Concrete Clients own networking,
framing, reconnect scheduling, stale callback isolation, and resource
teardown. They do not cache Worker commands or results.

`TextMessageClient.Listener` exposes only `onOpen`, `onMessage`, and the
exact-once `onEndpointTerminated`. Transient disconnect and failure evidence
does not cross into the Worker Runtime.

`TextMessageReconnectPolicy` bounds consecutive unstable connections. A
connection must survive its stable window before the count resets. Exhaustion
stops reconnecting and emits one `onEndpointTerminated()` callback, which ends
the current Worker run. A host must explicitly call `start()` to run again.

`WorkerRetryPolicy.defaults()` uses 10 preparation attempts one second apart,
plus 20 unstable connection attempts 500 milliseconds apart with a 10 second
stable window.

## Verification

```text
./gradlew :transport:worker-core:test
```
