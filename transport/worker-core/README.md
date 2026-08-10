# Worker Core

`transport:worker-core` is the Java 11 Worker mechanism shared by Java and
Android assemblies.

It owns:

- Worker event definitions, parameter resolution, dispatch, and outcome
  mapping.
- `WorkerPreparation` and the default Register/Bind implementation.
- `WorkerRunController`, the two-state coordinator for one explicit Worker
  run.
- `WorkerExecutionResources`, the non-owning Host injection contract for
  shared control and Handler execution.
- The package-private one-endpoint text-message runtime.
- Polling Worker behavior and platform-neutral network Client contracts.

It does not own concrete network libraries, Android storage, host process
lifecycle, threads, executors, schedulers, Adapter behavior, Redis, or Kernel
scheduling.

## Execution Resources

Every `WorkerRunController` receives a required
`WorkerExecutionResources` bundle:

```text
control ExecutorService          -> one asynchronous start task
handler ExecutorService          -> admitted WorkerEvent Handler execution
```

The Host creates these resources, may share one bundle across many Workers,
and closes them only after every consuming Worker is closed. Core never creates
or shuts down an executor. Closing one Controller does not affect another
Worker using the same bundle. Prepare and Handler execution remain separate so
a blocked business Handler cannot consume the control path.

For Java hosts, `JavaWorkerHostResources` is the process-scoped owner for one
bounded bundle. A `JavaWorkerManager` borrows that bundle and runs the fixed
replica set of exactly one WorkerGroup. Multiple Group Managers may share the
same resources; Managers close their Workers, and the process closes the
resource owner last. This does not move resource ownership or group desired
state into Core.

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

One accepted `WorkerRunController.start()` represents one complete Worker run:

```text
RUNNING
  -> exactly one asynchronous WorkerPreparation.prepare()
  -> PreparedWorker(workerId, endpointUri)
  -> one reconnecting TextMessageWorkerRuntime
  -> exact-once runtime terminal callback
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
`WorkerCommandExecutor`. `WorkerRunController` owns the one submitted start
task, current runtime reference, cooperative stop marker, and two-state
lifecycle observation. It never inspects command-busy state or routes
commands. Any Preparation failure ends that run in `STOPPED` with safe
diagnostic evidence. Only a later Host call to `start()` tries again.

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
`TextMessageClient` and reuse the prepared endpoint. The Runtime sends
`WorkerRunController` only one terminal fact; Bind admission, command busy state,
Handler progress, and reconnect activity never cross that owner boundary.
Endpoint termination, explicit stop, or a local Runtime infrastructure failure
ends the run after Handler cleanup. The current concrete Clients terminate an
endpoint when their reconnect budget is exhausted.

## Command And Result Ownership

`TASK`, `SYSTEM`, and `ADAPTER` commands all enter through the active
connection's inbound text callback. `WorkerLifecycle` exposes no local command
injection or message-send operation, and `WorkerRunController` never accepts or routes a
`WorkerCommand`. The runtime decodes the inbound frame and is the final
admission owner: it accepts only after the current open's Bind send was
accepted and while no command is executing. A malformed or inadmissible
inbound frame closes the current connection and is never queued. A frame that
arrives after terminal admission has closed is ignored.

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
snapshot
addListener / removeListener
```

The lifecycle has one observable axis:

```text
State  STOPPED / RUNNING
```

`RUNNING` begins when `start()` is accepted and includes its one Preparation,
Client connection/reconnect, command execution, and graceful stop while an
in-flight Handler finishes. Failures return to `STOPPED` and remain available
through `diagnosticMessage`; `close()` is an internal terminal object
condition rather than a third Worker state. The snapshot also carries the
current run's Worker ID and Endpoint URI metadata when preparation has
completed. It deliberately exposes no physical connection state or connection
query: Client reconnect is transparent and Worker lifecycle is not Kernel
online truth or scheduling availability. Listener calls are synchronous,
lightweight, and outside the lifecycle state lock. They may run on the
lifecycle caller, Host control/Handler executor, or Client callback thread.
Notifications are level observations and may repeat; `snapshot()` is the
authoritative current value rather than an ordered transition stream. Hosts
must move UI or blocking observation work onto their own appropriate executor.

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
does not cross into the Worker Runtime. The Client exposes no `isConnected`
or reconnect callback; `send()` acceptance is the Runtime's only per-frame
network evidence.

`TextMessageReconnectPolicy` bounds consecutive unstable connections. A
connection must survive its stable window before the count resets. Exhaustion
stops reconnecting and emits one `onEndpointTerminated()` callback, which ends
the current Worker run. A host must explicitly call `start()` to run again.

`TextMessageReconnectPolicy.defaults()` uses 20 unstable connection attempts
500 milliseconds apart with a 10 second stable window. Core has no Preparation
retry policy or retry scheduler.

## Verification

```text
./gradlew :transport:worker-core:test
```
