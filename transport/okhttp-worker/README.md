# XA Mass OkHttp Worker

Status: Java 11 compatible Worker library with OkHttp Polling/WebSocket and
line-oriented Socket transports.

`:transport:okhttp-worker` provides one serial Worker execution model and three
transport implementations:

```text
PollingWorkerTransport
  -> target Worker point poll
  -> execute at most one command
  -> point result submit

WebSocketWorkerTransport
  -> connect and send WorkerConnectionBind
  -> receive one command message
  -> execute serially
  -> send one result message

SocketWorkerTransport
  -> connect and write WorkerConnectionBind line
  -> read one command per line
  -> execute serially
  -> write one result per line
```

All transports delegate command semantics to `WorkerCommandProcessor`. The
processor:

1. rejects an expired command before execution;
2. decodes the nested `DeliverSeed`;
3. checks `DeliverSeed.workerId`;
4. selects a statically supplied `WorkerEventDefinition` by its String
   `eventCode`;
5. resolves the payload Map into the Handler parameter type;
6. maps completion and exceptions to `200/1400/1404/1500`;
7. preserves the opaque result context in `SeedResult`.

All Worker failures use the module's single `WorkerException` and numeric
`WorkerErrorCode` values in `30000..39999`, over the narrow
[`foundation_jvm`](../../foundation_jvm/README.md) contract. Transport,
protocol, and event categories are error-code ranges, not exception
subclasses. The Processor converts `EVENT_INPUT_INVALID` and
`EVENT_NOT_FOUND` to `1400` and `1404`; other Handler failures become `1500`.
Protocol failures continue to escape to the Transport boundary.

Retry logging uses JDK `System.Logger` directly:

```text
errorCode=31001 operation=polling.pollCommand workerId=<id> message=...
```

The library never logs nested delivery items, opaque result context, or full
business payloads. The exception stores no context map; the host's log or
trace call site adds Worker and execution context. Error codes classify
failures but do not independently decide retry, Worker outcome, or transport
acknowledgement.

Definitions bind parameter conversion and execution:

```java
WorkerEventDefinition<P> definition =
        WorkerEventDefinition.of(resolver, handler);
```

The registration key is always a String `eventCode`. A Handler returns the
already serialized, non-empty `opaqueResultPayload` String; `"null"` expresses
no business value. The framework does not require that String to contain JSON
and does not decode it again. Definitions are copied into an immutable
Manager; there is no runtime handler registration. The library does not
install example or business handlers and does not require deterministic
business results.

## Library Use

Create a processor and one transport:

```java
WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
WorkerCommandProcessor processor = new WorkerCommandProcessor(
        "worker-1",
        codec,
        Map.of(
                "sample.observe",
                WorkerEventDefinition.map(payload ->
                        Jsons.toJson(Map.of(
                                "observed",
                                payload.get("value")
                        )))
        )
);

PollingWorkerTransport transport = new PollingWorkerTransport(
        URI.create("http://127.0.0.1:18082"),
        WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID,
        "worker-1",
        Duration.ofSeconds(5),
        codec,
        processor
);
```

Typed Handler parameters use an explicit resolver without reflection:

```java
WorkerEventDefinition<ObserveParameters> observe =
        WorkerEventDefinition.of(
                payload -> new ObserveParameters(
                        (String) payload.get("value")
                ),
                parameters -> Jsons.toJson(Map.of(
                        "observed",
                        parameters.value()
                ))
        );
```

The host owns the thread and lifecycle. It may call `runOnce`, `runForever`,
`start`, and `close` according to the selected transport contract. Polling
retains one failed result and submits it before polling another command.
WebSocket and Socket retain one failed result across reconnect and resend it
after the next bind. These are in-memory best-effort guarantees, not durable
exactly-once delivery.

Long-lived transports first send:

```json
{"messageType":"WORKER_BIND","payload":"{\"workerId\":\"worker-1\"}"}
```

They then exchange the same stable outer message with different encoded
payload contracts:

```text
TASK_ITEM_COMMAND.payload -> WorkerCommandEnvelope
TASK_ITEM_RESULT.payload  <- encoded SeedResult
```

The Worker Definition Manager is statically assembled and local to this
module. The Transport decodes `WorkerConnectionMessage` once; the Definition
receives only its payload String, decodes `WorkerCommandEnvelope`, and passes
that command directly to `WorkerCommandProcessor`. The Processor constructs
the complete `SeedResult`; WebSocket and Socket serialize it before placing it
in the outbound `TASK_ITEM_RESULT` message.

Transport selection is independent of `TaskType`.

## Android Consumption

A repository Android application may consume the source module directly:

```gradle
dependencies {
    implementation project(':transport:okhttp-worker')
}
```

The library targets Java 11 bytecode and uses Android-compatible OkHttp for
Polling and WebSocket. It does not declare Android permissions, components,
services, foreground/background policy, or application lifecycle. A real
Android host owns those decisions and any desugaring required by its selected
toolchain and minimum SDK.

Device compatibility is not claimed until a real Android application or
integration module runs the library. This module deliberately does not keep an
empty Android wrapper solely as compatibility evidence.

## Boundary

This module:

- is a `java-library`, not an application;
- has no `mainClass`, CLI, or transport-mode policy;
- has no default business handlers;
- exposes no OkHttp types in public signatures;
- has no Android, Server, Kernel, Redis, score, Pacer, or TaskType dependency.

Verification:

```text
./gradlew :transport:okhttp-worker:test
```
