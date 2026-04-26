# Node Socket Worker Sample

Run:

```bash
node samples/worker-socket/node/worker.mjs
```

Required environment:

```text
WORKER_ID=node-worker-socket-001
SOCKET_HOST=127.0.0.1
SOCKET_PORT=18089
```

This sample uses the socket adapter handshake plus canonical
task-dispatch and task-result frames without WebSocket fallback.
