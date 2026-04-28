# Java WebSocket Worker Sample

Status: current runnable external worker sample.

Build from repo root:

```bash
./mvnw -f samples/worker-websocket/java/pom.xml -DskipTests package
```

Run:

```bash
java -jar samples/worker-websocket/java/target/worker-websocket-java-sample.jar
```

Required environment:

```text
WORKER_ID=java-worker-realtime-001
WS_URL=ws://127.0.0.1:18088/ws
```

This sample uses the realtime WebSocket adapter handshake plus canonical
task-dispatch and task-result frames.
