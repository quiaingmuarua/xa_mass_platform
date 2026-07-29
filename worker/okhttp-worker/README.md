# XA Mass OkHttp Worker

Status: Java 11 compatible Worker library with OkHttp Polling/WebSocket and
line-oriented Socket transports.

`:worker:okhttp-worker` provides one serial Worker execution model and three
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

Definitions bind parameter conversion and execution:

```java
WorkerEventDefinition<P, R> definition =
        WorkerEventDefinition.of(resolver, handler);
```

The registration key is always a String `eventCode`. The current Processor
requires each Definition to return `Map<String, Object>`, which it encodes as
the opaque success payload. Definitions are copied into an immutable Manager;
there is no runtime handler registration. The library does not install example
or business handlers and does not require deterministic business results.

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
                        Map.of("observed", payload.get("value")))
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
WorkerEventDefinition<ObserveParameters, Map<String, Object>> observe =
        WorkerEventDefinition.of(
                payload -> new ObserveParameters(
                        (String) payload.get("value")
                ),
                parameters -> Map.of(
                        "observed",
                        parameters.value()
                )
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
{"messageType":"WORKER_BIND","workerId":"worker-1"}
```

They then exchange the flat connection messages:

```text
TASK_ITEM_COMMAND -> TaskItemCommandMessage(WorkerCommandEnvelope)
TASK_ITEM_RESULT  -> TaskItemResultMessage(SeedResult)
```

Transport selection is independent of `TaskType`.

## Android Consumption

A repository Android application may consume the source module directly:

```gradle
dependencies {
    implementation project(':worker:okhttp-worker')
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
./gradlew :worker:okhttp-worker:test
```
