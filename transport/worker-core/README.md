# Worker Core

`transport:worker-core` is the Java 11 Worker mechanism shared by Java and
Android assemblies.

It owns:

- Worker event definitions, parameter resolution, dispatch, and outcome
  mapping.
- Final Definition registry composition from Core built-ins and Host-supplied
  extensions.
- `WorkerPreparation` and the default single-request Prepare implementation.
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
  -> one short owner-local run state and at most one installed Transport
  -> discarded Prepare results and stale Transport callbacks cannot install
     or end another run
  -> STOPPED/RUNNING observation

TextMessageWorkerTransportFactory
  -> prepared Endpoint + immutable Dispatcher -> Transport

TextMessageWorkerTransport
  -> Adapter-directed identity DeliveryReport on every physical open
  -> strict DeliveryCommand decode
  -> direct Adapter connection-close termination
  -> synchronous Dispatcher execution
  -> one-shot DeliveryReport send
  -> direct terminal callback delegated to the current Run owner

TextMessageClient
  -> protocol connection and framing
  -> protocol read order within one physical connection
  -> terminal close with no callback-completion guarantee
  -> bounded reconnect within one prepared Endpoint

WorkerCommandDispatcher
  -> immutable Definitions assembled from platform defaults + Host extensions
  -> synchronous Definition resolution and Handler execution
  -> optional WorkerCommandOutcome.succeeded(payload) / failed(WorkerErrorCode, payload)
```

Core creates or closes no execution resource. `WorkerRunController` submits
only Preparation to its injected Control `Executor`; active stop revokes the
run and closes its Client directly outside the state gate. Platform assemblies
own that Executor and all networking threads.

## Message Path

Every long-connection Command follows one synchronous path:

```text
Client protocol callback
  -> TextMessageWorkerTransport strict decode
  -> ADAPTER/worker.connection.close: end current run
  -> WorkerCommandDispatcher.execute
  -> WorkerEventDefinition(eventName, resolver, handler)
  -> optional WorkerCommandOutcome
  -> Transport creates DeliveryReport.fromCommand(WORKER, workerId, ...)
  -> one send attempt on the current connection
```

A physical Client connection preserves its protocol read order, so its
callbacks directly process Commands without a Core queue or Command executor.
Core does not serialize callbacks across replaced physical Attempts or Worker
runs. A callback admitted before close may finish after the Run is revoked,
and a new Run may execute concurrently with that old Handler. Definitions and
side effects shared by Worker instances or runs must therefore be thread-safe.

`WorkerCommandDispatcher` compares the Command deadline with the local system
epoch-millisecond clock, resolves the immutable `messageType` Event Name, and
invokes the resolver and handler. It
returns only `WorkerCommandOutcome`; it does not know workerId or construct a
protocol Report. Transport owns Report routing and Worker source identity;
Delivery defines no outer message or correlation ID.

TASK and DIRECT_CALL execution use this same path. A Host exposes an
additional Worker capability with
`WorkerEventDefinition.extension("device.snapshot", ...)`, which registers
the full name `extension.worker.device.snapshot`. Core does not add a
Direct mode, queue, executor, or special handler registry. Command
`src` remains invocation evidence and does not participate in Handler lookup.
`DeliveryReport.fromCommand()` naturally sends the result back to
`dst=SERVER` and preserves the Server-owned opaque `forward` value.

Workers create Dispatchers through `WorkerCommandDispatcher.forWorker()` or
`forWorker(definitions)`. Java and Android assemblies call
`WorkerManagementEventDefinitions.assemble(propertiesProvider, extensions)`
to copy Host extensions, prepend the platform Definitions and construct that
one immutable Dispatcher map:

| Event | Input | Result payload |
| --- | --- | --- |
| `platform.worker.probe` | `null` | `{"reachable":true}` |
| `platform.worker.properties.snapshot` | `null` | `{"properties":{...}}` |
| `platform.worker.events.snapshot` | `null` | sorted full `eventNames` |

Properties are loaded from the original Host provider on every snapshot. The
assembly-only `clientWorkerKey` is never injected into this result. The map
must be JSON-safe and the encoded result is capped below the one MiB transport
frame limit; invalid or unavailable properties map through the existing `3303`
execution failure. The Event snapshot is precomputed during assembly, includes
itself and all Host extensions in lexical order, and must fit the same result
limit. It is execution evidence for this Worker process, not WorkerGroup
`eventCodes`, authorization or schedulability. Duplicate full Event Names fail
assembly. Host extensions cannot construct or replace `platform.worker.*`
events. Runtime mutation and last-wins replacement are not supported.

An Event Name is the compatibility identity. Adding optional input or output
fields without changing existing meaning may retain the name. Incompatible
input, output, semantics or side effects require a new full name such as
`extension.worker.device.snapshot.v2`. Dispatcher lookup remains exact: Core
does not provide aliases, wildcard or prefix matching, dual lookup or fallback.

| Local diagnostic | Explicit outcome |
| --- | --- |
| empty string | Success: Handler returned a non-empty result payload |
| `3301` | Resolver or handler rejected event input |
| `3302` | No definition exists for `messageType` |
| `3303` | Handler execution failed |
| `3304` | Handler returned invalid output |

Success/failure is selected by the actual Dispatcher branch, not by parsing a
code. TextMessage and Polling produce the fixed `platform.worker.command.succeeded`
or `platform.worker.command.failed` event and retain the original Command
`forward` and opaque output. Report events are not installed in the Handler map.
Local typed errors still support Android capability HTTP status mapping;
Report diagnostic strings have no routing meaning.

Expired Commands are dropped before Handler invocation. A malformed frame or
an unexpected processing failure is logged with a Worker-owned `3xxx` code and
does not make the Worker reconnect. If a Result send is not accepted, the
Result is discarded. Long-connection Commands and Results are never queued, cached, or
replayed. Polling retains only its existing pending-result submission behavior;
later observations do not enter that pending execution Result.

## Later Task Outcome Observations

The existing single-argument Handler remains an ordinary RPC Handler. A Host
can use the two-argument `extension(...)` overload to retain a
`WorkerOutcomeReporter` after its synchronous execution returns. For example,
with a Host-owned messaging client and callback association:

```java
WorkerEventDefinition.extension(
    "message.send",
    WorkerEventParameterResolvers.jsonMap(),
    (parameters, reporter) -> {
        String externalId = messaging.send(parameters);
        messaging.onDelivered(externalId, time -> reporter.report(7, time, null));
        messaging.onRead(externalId, time -> reporter.report(8, time, null));
        messaging.onReply(externalId,
            (time, text) -> reporter.report(9, time, text));
        return "sent";
    }
);
```

`messaging` represents the Host's business integration; the Host owns association
and callback cleanup. Core keeps no Reporter collection. Java and Android use
the same Core overload and implementation, without a Task mode or creation flag.
The ordinary return still produces execution success and completes the execution
lease. Later reports refer to that same Item without managing Worker leases.

`report(tag, observedAtMillis, nullablePayload)` accepts tags 6..9, a positive
reported epoch-millisecond time, and optional nonblank content. Server also
checks the Score Owner time range. Transport binds the original opaque `forward`
and Worker send path; the Handler never parses Task, Item or Lease coordinates.
The exact event is `platform.worker.task-outcome.observed`, with WORKER source
and TASK destination. Non-TASK calls receive an unavailable Reporter returning
false, so DIRECT_CALL remains one response.

Reporting runs on the caller's thread and returns local acceptance, not delivery
or processing acknowledgement. Invalid input, oversize frames, closed/stopped
runs and send failures return false. Transparent reconnect keeps the same
Reporter's send path; stopping or closing its run prevents transfer to a new run.
Task completion or closure does not end a retained Item's outcome observation
window. That Item-level contract does not extend the Reporter's original run:
the Host still owns callback cleanup, and a stopped run's Reporter remains
unavailable even if the Item is retained.
WebSocket and Socket send through their existing Client. Polling uses the existing
point Report HTTP and never occupies or retries through `pendingResult`.
Core adds no queue, thread, automatic retry or callback-completion fence.

Server's example names are delivered/read/replied for 7/8/9. Kernel advances
generic terminal state; Result content separately prefers higher tag, then later
reported milliseconds. Equal times retain the stored content. State-only reports
do not erase content. Existing `items:states` and repeatable `results:load` queries
expose state and latest content independently. Evidence loss has no replay or
repair guarantee; detailed commit boundaries belong to the
[Result Owner](../../kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md).

On every physical connection open, Transport first sends
`DeliveryReport(src=WORKER,sourceId=workerId,dst=ADAPTER,`
`messageType=worker.connection.identify,`
`payload="null",forward="")`. `PreparedWorker` and the Transport factory carry
only the prepared workerId and Endpoint; WorkerGroup remains a Prepare
control-plane coordinate owned by Preparation. Identity has no message ID or
correlation value. A failed identity send asks
the Client to close the current physical connection and consume its normal
reconnect budget. A non-expired `ADAPTER/worker.connection.close` Command is
the only protocol event that directly ends the current run. It is consumed by
Transport and produces no DeliveryReport; it never enters the business
Dispatcher.

## One Worker Run

One accepted `WorkerRunController.start()` represents one complete run:

```text
RUNNING
  -> submit exactly one WorkerPreparation.prepare()
  -> PreparedWorker(workerId, endpointUri)
  -> create and start one TextMessageWorkerTransport
  -> Client reconnects within that Endpoint budget
  -> current Transport identity accepts one terminal transition
  -> STOPPED
```

Java Manager hosts may instead call `start(PreparedWorker)` with a coordinate
returned by their own explicit batch Prepare. That run uses the same state
fence and Transport creation path but does not invoke the Controller's
`WorkerPreparation`; the injected coordinate is not retained for a later run.
Core does not own the batch protocol or Properties aggregation.

`WorkerControlPreparation` owns `WorkerPropertiesProvider` and
`WorkerControlClient`. Each call loads one Properties map, validates and
copies its flat string KV entries, and performs exactly one Prepare request. Server resolves
the long-lived Worker ID from `workerGroupId + clientWorkerKey`, establishes
the Endpoint Binding, initializes minimal Kernel resources, and returns one
`PreparedWorker`. Core never persists Worker ID, starts networking, or executes
Commands during preparation. It requires `workerId` to be non-blank but does
not parse its Server-owned format.

Prepare carries the existing Map for Server-owned identity resolution; it does
not persist Worker Properties. First and later observations pass through Adapter
and Server admission into Matching. Local observation uses the same Host
Provider (`Map<String, String>`): non-blank keys, non-null
string values, empty strings allowed, and dots treated literally. Nested JSON,
arrays, numbers and booleans are rejected without coercion.

Java/Android `reportProperties()` reads that Provider once and sends
`platform.worker.properties.replaced`; the update overload
`reportProperties(Map<String, String> updates)` sends only its arguments as
`platform.worker.properties.updated`. The Host updates its own consistent
snapshot before sending an update.
Core retains no Properties copy, patch history, retry or queue. The Controller
captures its current Transport under the run gate, then performs Provider reads,
encoding and sending outside it. Inactive or unaccepted sends return false.
Invalid update arguments throw; Provider failures return false with safe
diagnostics and do not end the run.

On each verified connection Adapter sends one ADAPTER-origin
`platform.worker.properties.snapshot` Command. A successful output becomes a
single `WORKER -> ADAPTER platform.worker.properties.replaced` report: Core
extracts the Map from the successful snapshot output, without reading the Provider
again or sending an additional ordinary Result. TASK/SERVER snapshot calls keep
their `{"properties":{...}}` Result payload, destination and correlation.
Client onOpen still sends only identity; no ready state or ACK is added.
If the first request or upstream publication is lost, a new identity may remain
without Matching facts until explicit full reporting or a later connection
baseline. Polling has no equivalent Properties observation path; its new
Workers can use default Rule without a matching baseline.

Both report payloads are direct string KV Maps. `updated` overwrites supplied
keys and retains the rest; `replaced` replaces the whole Map and removes omitted
keys. Empty values stay present, and incremental deletion is not supported.
Empty updates preserve content; empty replacements establish an empty baseline.
`set`, `remove`, and `properties` are ordinary keys, not control fields. The
complete encoded Report must fit 1,000,000 UTF-8 bytes. A rejected
or lost report is not retained or retried. Already-admitted work may finish
after stop; the closed Client rejects its late send best effort. Concurrent
reports have no cross-Attempt ordering promise; explicit full reporting or a
later reconnect baseline can calibrate the Adapter cache. Adapter offers its
complete installed observation through SYSTEM; Server validates it before
Matching persists Worker facts. Core has no upstream publisher, retry, Matching
dependency or scheduling side effect. Loss still requires new Host input or a
later connection baseline; there is no unconditional eventual-delivery promise.

Preparation failure or Endpoint termination ends the run. Core does not retry
Preparation, schedule restart, or persist the Endpoint URI. A Host may
explicitly call `start()` again. Temporary disconnects remain inside the
current `TextMessageClient` and reuse the current URI.

During Preparation, `stop()` marks that one side-effectful Prepare result for
discard and keeps the call single-flight until it returns; no Transport can be
installed from that result. This is control-call convergence, not a paused
Worker. After a Transport is installed, `stop()` first commits `STOPPED` and
detaches the current Transport, then closes its Client outside the state gate.
It does not enter the Control Executor or wait for Adapter acknowledgement.
The Java WebSocket Client also does not wait for a Handler or Transport
callback: an admitted Handler may finish later and its Result uses the old
Client best-effort. `close()` is terminal, closes Preparation and the current
Client, and Core adds no callback-completion fence or cross-run pending Result.

## Lifecycle

`WorkerLifecycle` exposes `start`, `stop`, `close`, `snapshot`, and Listener
registration. `start()` submits control work without waiting for Prepare;
active `stop()` commits its state before Client teardown. Its observable state
is only `STOPPED / RUNNING`; `RUNNING` includes a queued or executing
Preparation, a discarded Prepare result waiting for that one call to return,
Client connection and reconnect, and synchronous Handler execution. An active
stop commits `STOPPED` directly; endpoint exhaustion and Adapter close Commands
also end the current run. The state does not assert physical connectivity,
Adapter route verification, Kernel online truth, scheduling availability, or
pause state.

Lifecycle Listener calls are synchronous best-effort level observations after
the owner transition. Notifications may repeat or race; `snapshot()` is
authoritative.
Hosts move UI or blocking observation work to their own platform execution
mechanism.

There is no local Command injection or Properties-refresh lifecycle method.
Platform and extension capabilities use statically assembled
`WorkerEventDefinition` values delivered through ordinary `DeliveryCommand`
messages. SERVER and TASK Commands share the same immutable Event Name map and
physical connection callback path. A Direct Command cannot preempt a TASK
Handler already executing on that same physical protocol path, but Core does
not prevent overlap with a replaced Attempt or a newly started Run. Worker
Core neither reads score nor implements authority priority; a synchronous
Handler must remain bounded and thread-safe.

## Client Boundary

Core exposes string-only `WorkerPointClient` and `TextMessageClient`, plus the
JDK-type-only `WorkerControlClient`. Per-run Client creation remains local to
`TextMessageWorkerTransportFactory` because the Endpoint URI is only known
after preparation.
Concrete Clients own I/O, framing, reconnect timers, physical-attempt
filtering, and callback admission. Their expensive infrastructure comes from
the platform assembly; closing one Client closes only that connection.

`TextMessageClient.Listener` exposes only `onOpen`, `onMessage`, and
`onEndpointTerminated`. A Client suppresses callbacks not yet admitted from
superseded physical connections. `close()` establishes no callback-
completion fence; callbacks already admitted may finish naturally, including
a callback that closes its own Client. The Java WebSocket implementation
returns immediately after committing terminal state and requesting socket
teardown. Different physical Attempts are not globally serialized. `send()`
reports only whether the current network stack accepted the frame.

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
