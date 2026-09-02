# Java WebSocket Worker Scale

`integrations:worker-websocket-scale` is the nightly/manual loaded-capacity
proof for one Java 21 Scenario Worker Host process, one `JavaWorkerManager`,
one WorkerGroup and one WebSocket Adapter Endpoint.

```text
15,000 prepared Worker identities
-> at least 14,800 connected-and-HOT
-> deterministically stop 5,000 runs
-> retain the exact 15,000 identities and exact 10,000 active target set
-> at least 9,900 active connected-and-HOT; stopped set is neither connected nor HOT
-> 10 fully seeded Tasks x 5,000 Items
-> 10 terminal Tasks and 50,000 exact success-only exports
-> active Fleet reconnects to connected-and-HOT after drain
-> restart Runtime Server while retaining the Host
-> same 10,000 active identities reconnect without start or Prepare
-> repeat the complete 50,000-Item loaded operation
```

The ten Tasks share one WorkerGroup, empty allocation rules and the same
candidate pool. Each Task has `maximumCandidateWorkers=100`. All ten are fully
populated before consecutive approval. The proof records their independent
progress but makes no fairness, execution-ratio or completion-order claim.
It is not a TPS/P99 benchmark, a 10,000 concurrent Handler claim or a soak test.

## Ownership

The Python runner owns process lifecycle, the exact Redis test scope, private
topology materialization, one Server restart, Linux `/proc` sampling and safe
evidence packaging. The Java Harness uses only the loopback Lab API and public
Runtime APIs. Network and Scheduling observations remain paged at 100 Worker
IDs; Result observation uses the public 1,000-ID `results:load` boundary.

Inventory is produced by the same strict materializer as the 100-Worker
Correctness and Convergence lanes. This lane supplies one 15,000-record Group
in 150 JSONL files. Materialization order defines the private topology: the
first 10,000 coordinates are retained and the final 5,000 are stopped. Public
evidence stores only counts and sorted worker-ID SHA-256 digests.

The Harness sends 50 one-shot Lab batch-stop requests of 100 coordinates. Any
failed or ambiguous request fails the mutation; the runner does not retry,
compensate or reshape the Fleet. Stopping a run does not delete its
Server-issued identity.

The generated Host assembly contains only
`extension.worker.string.md5`. The Runtime Server uses the checked
`scenario-workers` Profile plus the Integration-owned capacity configuration.
Redis, Server and Host are separate processes. Across Server restart the Host
PID is retained and no Lab start or Prepare endpoint is invoked; existing
Clients reconnect to the same Endpoint URI.

## Acceptance

The initial 15,000-Worker world requires three complete scans at or above
14,800 connected-and-HOT, then holds that threshold for 60 seconds. After
contraction, and again after Server restart, the active set requires three
scans at or above 9,900 connected-and-HOT. Every convergence scan also rejects
HOT-but-disconnected active Workers and any connected or HOT stopped Worker.

Each loaded operation creates ten Tasks and appends 5,000 valid Items to each
in 50 requests. The first successful Result must appear within 120 seconds;
after progress starts, a global 90-second success gap fails the lane. All Tasks
must become terminal within 900 seconds. Each Task is exported exactly once,
after terminal observation, and its success-only message-ID set must equal its
5,000 submitted IDs. Result payload remains opaque. During work only the 9,900
active connection threshold applies; HOT is re-established after drain.

The Worker Host and each Runtime Server process must remain below 512 native
Linux threads. The Host must remain below 32,768 open file descriptors and
each Server process below 16,384. RSS, cumulative CPU time and interval average
CPU core usage are recorded without cross-machine limits. Resources are
sampled every five seconds, with the pre- and post-restart Servers carrying
separate labels.

Run quick contracts on any development host:

```powershell
.\gradlew.bat :integrations:worker-websocket-scale:test
python -m unittest discover `
  -s integrations/worker-websocket-scale `
  -p "test_*.py"
```

The real proof requires Linux, Java 21, Redis 7.4, `nofile >= 65536` and a
local ephemeral port range containing at least 45,000 ports. Install the small
shared Python dependency set, widen the disposable host's port range, then run:

```bash
python -m pip install -r .github/scripts/requirements.txt
sudo sysctl -w net.ipv4.ip_local_port_range="10000 65535"
python integrations/worker-websocket-scale/run_worker_websocket_scale.py \
  --prepared-workers 15000 \
  --retained-workers 10000 \
  --minimum-initial-converged 14800 \
  --minimum-retained-converged 9900 \
  --workload-items-per-task 5000 \
  --redis-url redis://127.0.0.1:6379/15 \
  --output-root build/worker-websocket-scale-proof
```

The same command runs in `.github/workflows/worker-websocket-scale.yml`; this
high-cost proof is intentionally outside the ordinary pull-request gate.
