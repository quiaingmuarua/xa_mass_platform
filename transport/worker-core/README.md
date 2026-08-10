# Worker Core

`transport:worker-core` is the Java 11 Worker mechanism shared by Java and
Android assemblies.

It owns:

- Worker event definitions, parameter resolution, dispatch, and outcome
  mapping.
- Final Definition registry composition from Core built-ins and Host-supplied
  extensions.
- `WorkerPreparation` and the default Register/Bind implementation.
- `WorkerRunController`, the two-state coordinator for one explicit Worker
  run.
- `TextMessageWorkerTransportFactory` and the package-private one-endpoint
  `TextMessageWorkerTransport`.
- Polling Worker behavior and platform-neutral network Client contracts.

It does not own concrete networking, platform storage, Host process lifetime,
threads, executors, schedulers, connection registries, Adapter behavior,
Redis, or Kernel scheduling.

## Owners

```text
WorkerRunController
  -> one asynchronous start request on a platform-injected Control Executor
  -> exactly one Preparation per accepted start
  -> one current TextMessageWorkerTransport
  -> STOPPED/RUNNING observation

TextMessageWorkerTransportFactory
  -> prepared Endpoint + immutable Dispatcher -> Transport

TextMessageWorkerTransport
  -> Connection Bind
  -> strict WorkerCommand decode
  -> synchronous Dispatcher execution
  -> one-shot WorkerResult send
  -> exact-once endpoint termination

TextMessageClient
  -> protocol connection and framing
  -> ordered, non-overlapping Listener callbacks
  -> bounded reconnect within one prepared Endpoint

WorkerCommandDispatcher
  -> Core built-ins + immutable Host extensions
  -> synchronous Definition resolution and Handler execution
```

Core creates or closes no execution resource. `WorkerRunController` submits
start and stop work only to its injected Control `Executor`; platform
assemblies own that Executor and all networking threads.

## Message Path

Every long-connection Command follows one synchronous path:

```text
Client protocol callback
  -> TextMessageWorkerTransport strict decode
  -> WorkerCommandDispatcher.execute
  -> WorkerEventDefinition(src, eventCode, resolver, handler)
  -> optional WorkerResult
  -> one send attempt on the current connection
```

The Client contract serializes Listener callbacks for one Client. Therefore a
single Worker connection processes Commands in callback order without a Core
queue or Command executor. Different Worker connections can execute on their
respective protocol callback threads, so Definitions shared by Worker
instances must still be thread-safe.

`WorkerCommandDispatcher` compares the Command deadline with the local system
epoch-millisecond clock, resolves the immutable
`(src, messageType)` definition, invokes the resolver and handler, and
preserves Command correlation fields in the Result.

Workers create Dispatchers through `WorkerCommandDispatcher.forWorker()` or
`forWorker(definitionExtensions)`. Core owns the complete registry: it loads
its built-in Definitions first and appends a defensive copy of Host business
extensions. The built-in set is empty in this version. Duplicate
`(src, eventCode)` keys, including an extension attempting to replace a future
built-in, fail assembly; runtime mutation and last-wins replacement are not
supported.

| Outcome | Meaning |
| --- | --- |
| `200` | Handler returned a non-empty result payload |
| `1400` | Resolver or handler rejected event input |
| `1404` | No definition exists for `(src, messageType)` |
| `1500` | Handler failed or returned invalid output |

Expired Commands are dropped before Handler invocation. A malformed frame
closes the current connection with `PROTOCOL_ERROR`. If a Result send is not
accepted, the Result is discarded and the connection is closed with
`SEND_FAILURE`. Commands and Results are never queued, cached, or replayed.

## One Worker Run

One accepted `WorkerRunController.start()` represents one complete run:

```text
RUNNING
  -> submit exactly one WorkerPreparation.prepare()
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

Preparation failure or Endpoint termination ends the run. Core does not retry
Preparation, schedule restart, or persist the Endpoint URI. A Host may
explicitly call `start()` again. Temporary disconnects remain inside the
current `TextMessageClient` and reuse the current URI.

`stop()` first prevents new Commands and closes the Client. A Handler already
running on the Client callback completes, but its Result is discarded; the
Transport then terminates exactly once. `close()` is terminal and follows the
same Client callback fence. There is no cross-run pending Result.

## Lifecycle

`WorkerLifecycle` exposes `start`, `stop`, `close`, `snapshot`, and Listener
registration. `start()` and `stop()` are non-blocking requests. Its observable
state is only `STOPPED / RUNNING`; `RUNNING` includes a queued or executing
Preparation, Client connection and reconnect, Handler execution, and
cooperative stop. It does not assert physical connectivity, Adapter route
verification, Kernel online truth, or scheduling availability.

Lifecycle Listener calls are synchronous level observations outside the
lifecycle lock. Notifications may repeat; `snapshot()` is authoritative.
Hosts move UI or blocking observation work to their own platform execution
mechanism.

There is no local Command injection or Properties-refresh lifecycle method.
Platform and Adapter capabilities use statically assembled
`WorkerEventDefinition` values delivered through ordinary `WorkerCommand`
messages.

## Client Boundary

Core exposes string-only `WorkerPointClient`, `TextMessageClient`, and
`TextMessageClientFactory`, plus the JDK-type-only `WorkerControlClient`.
Concrete Clients own I/O, framing, reconnect timers, physical-attempt
filtering, and callback serialization. Their expensive infrastructure comes
from the platform assembly; closing one Client closes only that connection.

`TextMessageClient.Listener` exposes only `onOpen`, `onMessage`, and
exact-once `onEndpointTerminated`. A Client suppresses superseded physical
connection callbacks. External `close()` waits for a callback already in
progress; reentrant close from that callback is permitted. `send()` reports
only whether the current network stack accepted the frame.

`TextMessageReconnectPolicy` defaults to 20 unstable attempts, a 500
millisecond interval, and a 10 second stable window. The threadless
`TextMessageReconnectState` remains an optional helper for concrete Clients;
each Client still owns its timer and I/O actions.

`WorkerConnectionOptions.defaults()` groups the 10 second Control request
timeout with the default reconnect policy. Java and Android direct factories
accept this immutable value only when a Host needs non-default connection
settings.

`PollingWorkerTransport` remains a separate request-response mechanism and
accepts an already assembled `WorkerCommandExecutor`; it does not compose
Definition collections.

## Verification

```text
./gradlew :transport:worker-core:test
```
