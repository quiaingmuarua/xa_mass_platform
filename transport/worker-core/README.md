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

Java and Android assemblies construct one immutable
`WorkerCommandDispatcher` from their static definitions and inject it as a
`WorkerCommandExecutor`. `WorkerLoop` owns only preparation retry, the current
runtime reference, the result slot carried between automatic runtime rounds,
and lifecycle state. It never inspects command-busy or pending-result state.
Preparation network failures consume the bounded prepare budget. Exhaustion
or a contract failure enters `ERROR`; another round begins only after an
explicit `start()`.

The one-round runtime owns only:

```text
prepared workerId + endpoint Client
  -> onOpen: send WorkerConnectionBind
  -> send pending WorkerResult if present
  -> decode inbound WorkerCommand
  -> final command admission and serial execution
  -> retain/send the resulting WorkerResult
  -> report endpoint termination once
```

It receives only the prepared endpoint, a `WorkerCommandExecutor`, and the
shared result slot. It has no Identity Store, Properties provider, Control
Client, definitions, dispatcher construction, or preparation retry logic.
Ordinary disconnects, failures, and reconnects stay inside the current
`TextMessageClient` and reuse the prepared endpoint. Only endpoint termination
exits the runtime and returns control to `WorkerLoop`. The current concrete
Clients terminate an endpoint when its reconnect budget is exhausted.

## Command And Result Ownership

`TASK`, `SYSTEM`, and `ADAPTER` commands all enter through the active
connection's inbound text callback. `WorkerLifecycle` exposes no local command
injection or message-send operation, and `WorkerLoop` never accepts or routes a
`WorkerCommand`. The one-round runtime decodes the inbound frame and is the
final admission owner: it accepts only while connected, with no command
executing and no pending result. A malformed or inadmissible inbound frame
closes the current connection and is never queued.

If endpoint termination occurs during handler execution, that runtime waits
for the handler and places its result in the shared single slot before issuing
its exact-once exit callback. `WorkerLoop` then starts the next preparation
round without consulting command state. The next connection sends Bind before
the pending result. Network-stack acceptance clears the slot; this is not an
application ACK. Explicit `stop()`, terminal `ERROR`, and `close()` clear the
slot.

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
State            STOPPED / PREPARING / RUNNING / ERROR / CLOSED
ConnectionState  DISCONNECTED / CONNECTING / CONNECTED
```

`RUNNING` means a prepared one-round runtime is installed. `CONNECTED` means
the network opened and the workerId-only connection Bind was accepted by the
Client. Neither is Kernel online truth or scheduling availability.
`snapshot()` and `isConnected()` are current queries; lifecycle listeners do
not promise a callback for each transient network disconnect.

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
stops reconnecting and emits one `onEndpointTerminated()` callback. This ends
the current Client/runtime round; it does not stop the assembled Worker.

`WorkerRetryPolicy.defaults()` uses 10 preparation attempts one second apart,
plus 20 unstable connection attempts 500 milliseconds apart with a 10 second
stable window.

## Verification

```text
./gradlew :transport:worker-core:test
```
