# XA Mass JVM Worker Clients

`transport:okhttp-worker` is a Java 11 library containing concrete JVM network
Clients for the Worker mechanisms in
[`transport:core`](../core/README.md):

```text
OkHttpWorkerPointClient
  -> target Worker poll/result HTTP

OkHttpTextWebSocketClient
  -> text WebSocket connection and fixed reconnect

JdkLineSocketClient
  -> line-oriented TCP connection and fixed reconnect
```

This module does not own `PollingWorkerTransport`,
`WebSocketWorkerTransport`, or `SocketWorkerTransport`. It does not decode
Worker commands, create Bind or Result messages, run event handlers, or retain
pending Worker results.

## Explicit Composition

The host creates a concrete Client and passes it to the shared Transport:

```java
TextWebSocketClient client = new OkHttpTextWebSocketClient(
        URI.create(
                "ws://127.0.0.1:18083"
                        + "/api/v1/worker-delivery/websocket"
        ),
        Duration.ofSeconds(5),
        Duration.ofSeconds(1)
);

WebSocketWorkerTransport worker = new WebSocketWorkerTransport(
        client,
        workerId,
        eventDefinitions
);

worker.start();
```

`OkHttpTextWebSocketClient` accepts the final `ws/wss` URI and does not append
a Worker route. Polling composition uses `OkHttpWorkerPointClient` with the
Server base URL, endpoint manager ID, and Worker ID. Socket composition uses
`JdkLineSocketClient` with a final `tcp://host:port` URI.

The Worker Transport owns the supplied Client and closes it. A Client instance
must not be shared by multiple Worker Transports.

## Network Boundary

Clients expose only strings and connection events through Core interfaces.
They own URL/request handling, sockets, stale callback suppression, fixed
reconnect, and underlying network resources. They do not cache offline
business messages. A successful send means only that the network stack
accepted the message; no application ACK is added.

## Verification

```text
./gradlew :transport:okhttp-worker:test
```
