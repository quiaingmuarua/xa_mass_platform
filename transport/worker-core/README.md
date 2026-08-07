# Worker Core

`transport:worker-core` is the Java 11 Worker mechanism shared by Java and
Android assemblies.

It owns:

- Worker event definitions, parameter resolution, dispatch, and outcome
  mapping.
- `WorkerPreparation` and the default Register/Bind implementation.
- `WorkerLoop`, the long-lived lifecycle for one Worker identity.
- The package-private one-endpoint text-message runtime.
- Polling Worker behavior and platform-neutral network Client contracts.

It does not own concrete network libraries, Android storage, host process
lifecycle, Adapter behavior, Redis, or Kernel scheduling.

## Execution

Every command reaches the same DTO entry:

```text
encoded network input
  -> strict WorkerCommand decode at the transport boundary
  -> WorkerLoop.send(WorkerCommand)
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

## Three Stages

One `WorkerLoop` represents one long-lived Worker identity:

```text
WorkerPreparation
  -> PreparedWorker(workerId, endpointUri)
  -> one TextMessageWorkerRuntime round
  -> exact-once runtime exit callback
  -> WorkerPreparation again
```

`RegisteredWorkerPreparation` owns `WorkerIdentityStore`,
`WorkerPropertiesProvider`, and `WorkerControlClient`. Each call reads one
complete immutable Properties snapshot, restores or registers the Worker ID,
persists a newly issued ID, and performs Endpoint Bind. It does not start a
network connection or execute commands.

`WorkerLoop` owns static definitions, one command executor, one pending-result
slot, preparation retry policy, and the current runtime reference. Preparation
network failures consume the bounded prepare budget. Exhaustion or a contract
failure enters `ERROR`; another round begins only after an explicit `start()`.

The one-round runtime owns only:

```text
prepared workerId + endpoint Client
  -> onOpen: send WorkerConnectionBind
  -> send pending WorkerResult if present
  -> decode inbound WorkerCommand
  -> call WorkerLoop.send
  -> report reconnect exhaustion once
```

It has no Identity Store, Properties provider, Control Client, definitions,
dispatcher, or preparation retry logic. Ordinary reconnects stay inside the
current `TextMessageClient` and reuse the prepared endpoint. Only reconnect
budget exhaustion exits the runtime and returns control to `WorkerLoop`.

## Command And Result Ownership

Adapter commands and local `SYSTEM` or `ADAPTER` commands use the same
`WorkerLoop.send(WorkerCommand)` method. It returns `true` only while the
Worker is running and connected, with no command executing and no pending
result. Rejected commands are not queued and callers decide whether to retry.

If a runtime exits during handler execution, the handler completes before a
new runtime is installed. Its result enters the Worker-owned single slot and
crosses that automatic runtime replacement. The next connection sends Bind
before the pending result. Network-stack acceptance clears the slot; this is
not an application ACK. Explicit `stop()`, terminal `ERROR`, and `close()`
clear the slot.

`PollingWorkerTransport` remains a separate request-response mechanism. It
decodes the point response before calling the same DTO executor and submits a
pending result before polling another command.

## Lifecycle

`WorkerLifecycle` exposes:

```text
start / stop / close
send(WorkerCommand)
snapshot / isConnected
addListener / removeListener
```

Observation has two independent axes:

```text
State            STOPPED / PREPARING / RUNNING / ERROR / CLOSED
ConnectionState  DISCONNECTED / CONNECTING / CONNECTED
```

`RUNNING` means a prepared one-round runtime is installed. `CONNECTED` means
the network opened and the workerId-only connection Bind was accepted by the
Client. Neither is Kernel online truth or scheduling availability.

There is intentionally no direct Properties-refresh lifecycle method.
Platform or Adapter initiated behavior is expressed through statically
installed `WorkerEventDefinition` commands and the same `send` path.

## Client Boundary

Core exposes string-only `WorkerPointClient` and `TextMessageClient`, plus the
JDK-type-only `WorkerControlClient`. Concrete Clients own networking,
framing, reconnect scheduling, stale callback isolation, and resource
teardown. They do not cache Worker commands or results.

`TextMessageReconnectPolicy` bounds consecutive unstable connections. A
connection must survive its stable window before the count resets. Exhaustion
stops reconnecting and emits one `onReconnectExhausted()` callback.

`WorkerRetryPolicy.defaults()` uses 10 preparation attempts one second apart,
plus 20 unstable connection attempts 500 milliseconds apart with a 10 second
stable window.

## Verification

```text
./gradlew :transport:worker-core:test
```
