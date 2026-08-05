# Android WebSocket Client

`transport:android-client` is an internal Android library containing one
concrete implementation of Core's `TextWebSocketClient`. It is consumed through
a repository-local Gradle project dependency and is not a published SDK or a
second Worker implementation.

`AndroidOkHttpTextWebSocketClient` owns:

- OkHttp WebSocket connection and fixed-interval reconnect.
- A dedicated HandlerThread as the single writer of connection state.
- Serialized listener callbacks and generation filtering for stale sockets.
- A thread-safe current-connection snapshot used by non-blocking `send`.

It does not know Worker IDs, connection Bind frames, commands, results, event definitions, or
pending business results. It has no offline message queue.

## Start A Worker

The Android host explicitly composes the three layers:

```java
List<WorkerEventDefinition<?>> definitions = List.of(
        WorkerEventDefinition.of(
                "TASK",
                "test.observe",
                WorkerEventParameterResolvers.jsonMap(),
                parameters -> Jsons.toJson(Map.of(
                        "observed",
                        parameters.get("value")
                ))
        )
);

TextWebSocketClient client =
        new AndroidOkHttpTextWebSocketClient(
                URI.create(
                        "ws://10.0.2.2:18083"
                                + "/api/v1/worker-delivery/websocket"
                ),
                Duration.ofSeconds(5),
                Duration.ofSeconds(1)
        );

WebSocketWorkerTransport worker =
        new WebSocketWorkerTransport(
                client,
                workerId,
                definitions
        );

worker.start();
```

The host obtains a long-lived platform Worker ID through Register, then calls
Bind with its WorkerGroup, persisted client key, requested transport, and
complete Worker Properties snapshot. It constructs this Client from the
returned endpoint URI and supplies only `workerId` to the shared Transport.
Register and Bind are outside this network Client module.

The host retains the `WebSocketWorkerTransport` and calls `worker.close()` when
its own lifetime ends. The Transport closes the Android Client. This module
does not install an Activity, Service, WorkManager, static singleton, or
process-lifetime policy.

## State And Retry

Connection state is private to the Client:

```text
NEW -> CONNECTING -> CONNECTED
                    -> RECONNECT_SCHEDULED -> CONNECTING
NEW/CONNECTING/CONNECTED/RECONNECT_SCHEDULED -> CLOSED
```

Reconnect uses one fixed positive interval until terminal `close`. `send`
returns false when no current connection accepts the text. It never queues the
message; pending Worker Result ownership remains in
`WebSocketWorkerTransport`.

## Verification

```text
./gradlew :transport:android-client:testDebugUnitTest
./gradlew :transport:android-client:assembleDebug
```
