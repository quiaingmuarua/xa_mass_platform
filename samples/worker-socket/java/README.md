# Java Socket Worker Sample

Build from repo root:

```bash
./mvnw -f samples/worker-socket/java/pom.xml -DskipTests package
```

Run:

```bash
java -jar samples/worker-socket/java/target/worker-socket-java-sample.jar
```

Required environment:

```text
WORKER_ID=java-worker-socket-001
SOCKET_HOST=127.0.0.1
SOCKET_PORT=18089
```

This sample uses the socket adapter handshake plus canonical
task-dispatch and task-result frames without WebSocket fallback.
