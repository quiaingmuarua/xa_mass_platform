# Node WebSocket Worker Sample

Run from repo root:

```bash
node samples/worker-websocket/node/worker.mjs
```

Required environment:

```text
WORKER_ID=node-worker-realtime-001
WS_URL=ws://127.0.0.1:18088/ws
```

This sample uses the realtime WebSocket adapter handshake plus canonical
task-dispatch and task-result frames.
