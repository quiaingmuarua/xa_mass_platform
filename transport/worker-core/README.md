# Worker Core

`transport:worker-core` is the Java 11 Worker mechanism shared by Java and
Android assemblies.

It owns:

- Worker event definitions, parameter resolution, dispatch, and outcome
  mapping.
- `WorkerPreparation` and the default Register/Bind implementation.
- `WorkerRunController`, the two-state coordinator for one explicit Worker
  run.
- The package-private one-endpoint `TextMessageWorkerTransport`.
- Synchronous Command dispatch, Host Executor handoff, and the text Client
  factory port.
- The threadless reconnect generation and stability state used by concrete
  Clients.
- Polling Worker behavior and platform-neutral network Client contracts.

It does not own concrete network libraries, Android storage, Host process
lifecycle, threads, executors, schedulers, connection registries, Adapter
behavior, Redis, or Kernel scheduling.

## Owners

```text
WorkerRunController
  -> one synchronous Preparation
  -> one current TextMessageWorkerTransport
  -> STOPPED/RUNNING observation

TextMessageWorkerTransport
  -> Connection Bind
  -> strict WorkerCommand decode and admission
  -> correlated WorkerResult send
  -> exact-once endpoint termination

TextMessageClient
  -> protocol connection and framing
  -> reconnect within one prepared Endpoint
  -> stale connection generation filtering

WorkerCommandDispatcher
  -> synchronous Definition resolution and Handler execution

Host Command Executor
  -> thread handoff and execution-capacity admission
```

Core creates and closes no execution resources. The Transport submits one
`Runnable` to a Host-provided `Executor`, then calls the synchronous
`WorkerCommandExecutor` inside that Runnable. Java and Android Host Resources
use fixed pools with `SynchronousQueue`; `RejectedExecutionException` means the
Command obtained no execution capacity and will never run later. There is no
separate asynchronous business execution contract.

## Execution

Every text Command follows one path:

```text
raw text
  -> TextMessageWorkerTransport strict decode
  -> reject duplicate in-flight messageId
  -> Host Command Executor.execute
  -> WorkerCommandDispatcher.execute synchronously
  -> WorkerEventDefinition(src, eventCode, resolver, handler)
  -> optional WorkerResult
  -> one send attempt on the current bound connection
```

`WorkerCommandDispatcher` is synchronous and immutable. It checks the
deadline, resolves the static `(src, messageType)` definition, invokes the
resolver and handler, and preserves the Command correlation fields in the
Result. Distinct message IDs may execute concurrently, so Definitions and
Handlers must be thread-safe whenever the Host configures concurrency above
one.

| Outcome | Meaning |
| --- | --- |
| `200` | Handler returned a non-empty result payload |
| `1400` | Resolver or handler rejected event input |
| `1404` | No definition exists for `(src, messageType)` |
| `1500` | Handler failed, returned invalid output, or no execution slot was available |

Expired Commands are dropped before Handler invocation. A deadline is not
rechecked after execution starts.

The Transport has no Command queue and no Result cache. A malformed frame or
duplicate in-flight message ID closes the current connection. Capacity
rejection sends an immediate correlated `1500` without closing the connection.
Results may be returned out of order. If a Result cannot be sent on the
current connection, it is dropped and the current connection is closed for a
send failure; reconnect never replays it.

## One Worker Run

One accepted `WorkerRunController.start()` represents one complete run:

```text
RUNNING
  -> exactly one synchronous WorkerPreparation.prepare()
  -> PreparedWorker(workerId, endpointUri)
  -> create and start one TextMessageWorkerTransport
  -> Client reconnects within that Endpoint budget
  -> exact-once Transport terminal callback
  -> STOPPED
```

`RegisteredWorkerPreparation` owns `WorkerIdentityStore`,
`WorkerPropertiesProvider`, and `WorkerControlClient`. Each call loads one
immutable Properties snapshot, restores or registers the Worker ID, persists
a newly issued ID, and performs Endpoint Bind. It does not start networking or
execute Commands.

Preparation failure or Endpoint termination ends the current run. Core does
not retry Preparation, schedule restart, or persist the Endpoint URI. A Host
may explicitly call `start()` again. Temporary disconnects remain inside the
current `TextMessageClient` and reuse the current URI.

Graceful `stop()` and Endpoint termination stop admitting new Commands, wait
for already accepted executions to complete, discard their late Results, and
then finish the run. Immediate `close()` tears down the current Client without
waiting for or cancelling tasks in the shared Host executor.

## Lifecycle

`WorkerLifecycle` exposes:

```text
start / stop / close
snapshot
addListener / removeListener
```

Its observable state is only `STOPPED / RUNNING`. `RUNNING` includes
Preparation, Client connection and reconnect, Command execution, and graceful
stop. It does not assert physical connectivity, Adapter route verification,
Kernel online truth, or scheduling availability. Snapshot identity and
Endpoint fields describe only the current prepared run.

Listener calls are synchronous, lightweight level observations outside the
lifecycle state lock. Notifications may repeat and may occur on the lifecycle
caller, a Client callback lane, or a Command execution lane. `snapshot()` is
the authoritative current value; Hosts must move UI or blocking observation
work to the appropriate platform executor.

There is no local Command injection or Properties-refresh lifecycle method.
Platform and Adapter capabilities continue to use statically assembled
`WorkerEventDefinition` values delivered through ordinary `WorkerCommand`
messages.

## Client Boundary

Core exposes string-only `WorkerPointClient`, `TextMessageClient`, and
`TextMessageClientFactory`, plus the JDK-type-only `WorkerControlClient`.
Concrete Clients own I/O, framing, reconnect scheduling, and stale callback
suppression. Their expensive infrastructure is borrowed from platform Host
Resources; closing one Client closes only that connection. A Client never
decodes Worker DTOs or caches business messages.

`TextMessageClient.Listener` exposes only `onOpen`, `onMessage`, and exact-once
`onEndpointTerminated`. The Client exposes no physical connection query or
transient reconnect event. `send()` reports only whether the current network
stack accepted that frame.

`TextMessageReconnectState` is a synchronized, threadless generation and
stability state machine shared by the concrete Clients. The default policy is
20 unstable attempts, a 500 millisecond reconnect interval, and a 10 second
stable window. Concrete platform Clients still own every timer and I/O action.

`PollingWorkerTransport` remains a separate request-response mechanism.

## Verification

```text
./gradlew :transport:worker-core:test
```
