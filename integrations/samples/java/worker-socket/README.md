# Java Socket Worker Sample

Status: current runnable external worker sample.

Build from repo root:

```bash
./mvnw -f integrations/samples/java/worker-socket/pom.xml -DskipTests package
```

Run:

```bash
java -jar integrations/samples/java/worker-socket/target/worker-socket-java-sample.jar
```

Required environment:

```text
WORKER_ID=java-worker-socket-001
SOCKET_HOST=127.0.0.1
SOCKET_PORT=18089
```

This sample uses the socket adapter handshake plus canonical
task-dispatch and task-result frames without WebSocket fallback.
