# Android Transport Client

`transport:android-client` is an internal Android library containing the
Android-specific WebSocket network client and the production composition entry
for one WebSocket Worker. It is consumed as a Gradle project dependency and is
not published as an SDK.

`AndroidOkHttpTextWebSocketClient` implements the shared
`TextWebSocketClient` contract. It owns only:

- OkHttp WebSocket connection and fixed-interval reconnect.
- A dedicated `HandlerThread` that serializes connection state and callbacks.
- Connection-generation filtering for stale callbacks.

It does not know Worker IDs, bind messages, commands, results, event
definitions, or pending business results.

## Start A Worker

The Android host registers its event definitions and starts one production
composition:

```java
AndroidWebSocketWorker worker = new AndroidWebSocketWorker(
        URI.create("ws://10.0.2.2:18083/api/v1/worker-delivery/websocket"),
        "worker-android-1",
        Duration.ofSeconds(5),
        Duration.ofSeconds(1),
        List.of(WorkerEventDefinition.of(
                "TASK",
                "test.observe",
                WorkerEventParameterResolvers.jsonMap(),
                parameters -> Jsons.toJson(Map.of(
                        "observed",
                        parameters.get("value")
                ))
        ))
);

worker.start();
```

The host retains the instance and calls `worker.close()` when its own lifetime
ends. `AndroidWebSocketWorker` owns no Android Service, Activity, static
singleton, or background-process policy.
