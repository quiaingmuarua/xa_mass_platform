# Worker Core

`transport:worker-core` is the Java 11 Worker mechanism shared by JVM and
Android network clients.

It owns:

- Worker event definitions, parameter resolution, dispatch, and error
  classification.
- The transport-neutral `WorkerCommandExecutor`.
- Polling and one protocol-neutral long-lived text-message Worker Transport.
- String-only point and long-lived text-message Client interfaces.
- The platform-neutral `WorkerControlClient` Register/Bind contract and
  `WorkerTransportType`.
- `WorkerLifecycle`, the shared lifecycle, state, snapshot, and listener
  contract implemented by assembled Java and Android Workers.
- `TextMessageWorkerRuntime`, `WorkerIdentityStore`, and
  `WorkerPropertiesProvider`, which define the shared Java/Android startup
  lifecycle.

It does not own network implementations, Adapter behavior, host process
lifecycle, Redis access, or Kernel scheduling.

## Execution Contract

`WorkerCommandDispatcher` is the default `WorkerCommandExecutor`. It:

```text
decode one direct WorkerCommand
  -> reject malformed protocol input
  -> drop an already-expired command before handler invocation
  -> resolve one statically supplied (src, messageType) definition
  -> invoke its parameter resolver and handler
  -> return one direct WorkerResult with correlation/forward unchanged
```

The default outcome mapping is:

| Outcome | Meaning |
| --- | --- |
| `200` | Handler returned a non-empty result payload |
| `1400` | Command payload or resolved handler input is invalid |
| `1404` | No definition exists for the `(src, messageType)` pair |
| `1500` | Handler failed or returned an empty result payload |

A malformed encoded command is a protocol failure and does not produce a
`WorkerResult`. A command at or beyond `executeBeforeMillis` is dropped before
the handler starts. Once a handler starts, the deadline is not checked again.
Callers may supply a custom `WorkerCommandExecutor`; doing so replaces only
command execution policy, not Transport protocol state.

## Transport State Machines

The Polling and long-lived Worker Transports accept either caller definitions
or a custom executor together with the matching string-only Client interface:

```java
TextMessageWorkerTransport worker =
        new TextMessageWorkerTransport(
                textMessageClient,
                workerId,
                eventDefinitions
        );
```

Their stable behavior is:

| Transport | Host operation | Protocol behavior |
| --- | --- | --- |
| `PollingWorkerTransport` | `runOnce()` or `runForever(interval)` | Submits a pending result before polling another command; its point Client targets the URI returned by Bind |
| `TextMessageWorkerTransport` | `start()` or `runForever()` | Sends `WorkerConnectionBind(workerId)` first, serializes command execution, and replays a pending result after reconnect for either WebSocket or line Socket |

All three transports receive only the platform-issued `workerId`. Registration,
Endpoint Binding, and Worker Properties refresh happen before Transport
construction. WebSocket and Socket exchange a direct connection Bind frame,
`WorkerCommand`, and `WorkerResult` JSON values. There is no generic
connection-message envelope. A long-lived Transport accepts a command only
after the connection Bind frame has been handed to the network Client and while no
command is processing and no result is pending.

Each Transport retains at most one pending result. Polling retains it until the
point result submission succeeds. The text-message Transport retains it until the
active Client accepts the encoded result; after reconnect it sends the Bind
frame first and then resends it. A Runtime-owned single slot survives automatic
Transport replacement within one `start()` generation. Explicit `stop()`,
`close()`, or terminal `ERROR` clears it. Client acceptance is not an
application ACK, and Worker Core does not add a pending/ack ledger.

The Transport owns and closes its Client. Polling `close()`, and long-lived
`start()`/`close()`, are idempotent at their lifecycle boundaries. A closed
Transport cannot be restarted. Concrete WebSocket Clients reject binary input
with close code `1003`; invalid Worker message sequencing is rejected by the
shared Transport with `CloseReason.PROTOCOL_ERROR`.

The optional `TextMessageWorkerTransport.Observer` reports Transport-level
readiness only after the connection Bind has been accepted by the Client. Host
lifecycle code observes this boundary rather than attaching a second listener
to the underlying network Client.

## Client Boundary

Core exposes only:

```text
WorkerPointClient
TextMessageClient
WorkerControlClient
```

The network interfaces carry strings plus connection/lifecycle signals.
`WorkerControlClient` expresses only long-lived identity Register and Endpoint
Bind using JDK types. Register and Bind both receive the same complete,
immutable Worker Properties snapshot, including the framework-reserved
`clientWorkerKey`. None of the Client contracts exposes concrete
networking or Android types. Concrete Clients own network requests,
connections, reconnect, stale-callback isolation where applicable, and
resource teardown. They must not cache Worker business messages; pending
result ownership remains in the corresponding Worker Transport.

The text-message Client contract requires serialized listener callbacks, one
terminal callback per connection generation, stale-connection isolation,
thread-safe non-blocking `send`, idempotent lifecycle, and no callbacks after
`close()` returns. `TextMessageReconnectPolicy` bounds consecutive unstable
connections. Opening a connection does not reset the count; only remaining
connected for the configured stable window does. Once the budget is exhausted,
the Client stops reconnecting and emits exactly one `onReconnectExhausted()`.
WebSocket and line Socket implementations own framing and map `CloseReason` to
their protocol-specific close operation.

## Text-message Runtime

`WorkerLifecycle` is the common public operation and observation surface.
Platform Workers remain final composition facades and delegate that contract
to `TextMessageWorkerRuntime`; they do not inherit a lifecycle template or
copy the Core state model.

`TextMessageWorkerRuntime` owns a two-level Java/Android host lifecycle:

The Runtime owns one `WorkerControlClient` for its full lifetime. Prepare
retries, later starts, and Properties refresh are serialized through that
Client; only terminal `close()` releases it.

```text
outer supervisor
  -> load one complete Properties snapshot
  -> load the long-lived workerId
  -> Register and save it only when absent
  -> retry Endpoint Bind within the prepare budget
  -> construct TextMessageWorkerTransport with the returned URI

inner Client
  -> connect and reconnect within the connection budget
  -> after reconnect exhaustion notify the supervisor
  -> supervisor closes the old Transport and prepares again
```

Temporary reconnects reuse the current URI and do not call Register or Bind.
Connection-budget exhaustion starts a new prepare round, reloads Properties,
and Bind may return the same or a different URI. The prepare-attempt budget
applies to one such round; it is not a lifetime limit on supervisor rounds. If
each Bind succeeds while connections keep exhausting, the supervisor continues
until `stop()` or `close()`. `stop()` retains the Worker ID, while a later
explicit `start()` always performs Bind again.
`refreshProperties()` rebinds only when the canonical snapshot changes and
requires the returned URI to remain unchanged. A changed URI enters `ERROR` so
the local connection cannot silently diverge from the latest Bind response.
Control network errors and HTTP 5xx consume the fixed prepare budget; contract
rejection, malformed state, and Identity persistence failure enter `ERROR`
immediately. Exhausting one prepare round also enters `ERROR`, and only an
explicit later `start()` begins again.

`WorkerRetryPolicy.defaults()` fixes prepare at 10 attempts separated by one
second, and connection recovery at 20 consecutive unstable terminations
separated by 500 milliseconds with a 10 second stable window.

Lifecycle observation has three independent axes:

```text
State             STOPPED / STARTING / RUNNING / ERROR / CLOSED
PrepareOperation  NONE / REGISTERING / BINDING
ConnectionState   DISCONNECTED / CONNECTING / CONNECTED
```

`ConnectionState` is derived from the currently owned Transport rather than
stored as a second connection truth inside the Runtime.

`RUNNING` only means prepare produced a local Transport. `CONNECTED` only
means the network opened and the workerId-only connection Bind was handed to
the network stack. Neither is Kernel online truth or scheduling availability.
The Runtime fixes one `WorkerTransportType` at construction; `WEBSOCKET` and
`SOCKET` are supported and `POLLING` remains a separate request-response
lifecycle.

## Verification

```text
./gradlew :transport:worker-core:test
```
