# Java WebSocket Worker Scale

`integrations:worker-websocket-scale` is the offered-load proof for one Java 21
Scenario Worker Host process, one `JavaWorkerManager`, one WorkerGroup, and one
WebSocket Adapter Endpoint.

```text
10,000 prepared Worker identities
-> 10,000 offered WebSocket connections
-> Runtime Network and Scheduling observations
-> minimum 9,900 connected-and-HOT Workers
-> 100 finite TaskItems
-> one Runtime Server restart
-> transparent reconnect without Host restart or Prepare
-> minimum 9,900 connected-and-HOT Workers
-> another 100 finite TaskItems
```

This is a connection-scale and finite recovery proof. It is not a 10,000
concurrent Handler claim, Task throughput benchmark, latency SLA, long-running
soak test, or exact 10,000-online guarantee.

The native-thread threshold covers both the OkHttp Dispatcher and OkHttp's
internal WebSocket TaskRunner. A reconnect burst that recreates thousands of
platform TaskRunner threads fails the lane even when all connections recover.

## Ownership

The Python runner owns processes, the exact Redis
test scope, one Server restart, Linux `/proc` sampling, and safe evidence
packaging. The Java Harness uses only the loopback Lab API and public Runtime
APIs. It pages existing Network and Scheduling observations in groups of 100;
it does not widen Server APIs or read Redis.

Inventory is produced by the same strict materializer used by the 100-Worker
Correctness and Convergence lanes. This lane supplies one 10,000-record scale
world; the materializer alone owns deterministic 100-record JSONL splitting.

The generated Host assembly contains only the existing
`extension.worker.string.md5` capability. One hundred JSONL files contain one
hundred strict records each. The complete Server-issued Worker ID set is kept
in a private inter-phase file so the post-restart phase can compare identity;
uploaded evidence retains only its count and sorted SHA-256 digest.

The Runtime Server uses the checked `scenario-workers` Profile plus the
Integration-owned Adapter capacity override. Redis, Server, and Host remain
separate processes. The Host remains alive across the Server restart and does
not Prepare again; its concrete WebSocket Clients reconnect to the same
Endpoint URI.

## Acceptance

Initial and post-restart convergence each require three consecutive complete
scans with at least 9,900 Workers in both `connected` Network state and a HOT
Scheduling projection. The converged final scan rejects any HOT Worker that is
observed disconnected. The initial phase then holds the 9,900 threshold for 60
seconds with scans every 10 seconds.

The runner also requires the Worker Host to remain below 512 native Linux
threads. Virtual threads are not counted as native `/proc` threads; this guard
detects accidental one-platform-thread-per-WebSocket behavior. RSS, native
threads, and open file descriptors are sampled every five seconds.

Run the quick contract tests on any development host:

```powershell
.\gradlew.bat :integrations:worker-websocket-scale:test
python -m unittest discover `
  -s integrations/worker-websocket-scale `
  -p "test_*.py"
```

Run the real proof on Linux with Java 21, Redis 7.4, `nofile >= 65536`, and an
ephemeral port range containing at least 20,000 ports. Python 3.11 or newer and
the shared proof dependencies are required:

```bash
python -m pip install -r .github/scripts/requirements.txt
```

Then run:

```bash
python integrations/worker-websocket-scale/run_worker_websocket_scale.py \
  --workers 10000 \
  --minimum-converged 9900 \
  --redis-url redis://127.0.0.1:6379/15 \
  --output-root build/worker-websocket-scale-proof
```

The same command runs nightly and on manual dispatch in
`.github/workflows/worker-websocket-scale.yml`; it is intentionally outside the
ordinary pull-request Proof Gate.
