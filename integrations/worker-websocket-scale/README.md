# Java WebSocket Worker Scale

`integrations:worker-websocket-scale` is the nightly/manual loaded-capacity
proof for one Java 21 Scenario Worker Host process, one `JavaWorkerManager`,
one WorkerGroup and one WebSocket Adapter Endpoint.

```text
15,000 prepared Worker identities
-> at least 14,800 connected-and-HOT
-> begin ten fully seeded Tasks x 5,000 Items
-> deterministically stop 5,000 runs while work is active
-> retain the exact 15,000 identities and exact 10,000 active target set
-> finish the original 50,000 Items and reconverge
-> repeat a 50,000-Item operation across graceful Server restart
-> repeat across hard Server restart
-> repeat across a second hard Server restart
-> same 10,000 active identities recover without start or Prepare
-> 40 terminal Tasks and 200,000 exact success-only exports
```

The ten Tasks share one WorkerGroup, empty allocation rules and the same
candidate pool. Each Task has `maximumCandidateWorkers=100`. All ten are fully
populated before consecutive approval. The proof records their independent
progress but makes no fairness, execution-ratio or completion-order claim.
It is not a TPS/P99 benchmark, a 10,000 concurrent Handler claim or a soak test.

## Ownership

The Python runner owns process lifecycle, the exact Redis test scope, private
topology materialization, one SIGTERM and two SIGKILL Server mutations, Linux
`/proc` sampling and safe evidence packaging. The Java Harness uses only the
loopback Lab API and public Runtime APIs. It retains each stage's ten Tasks in
one process across the Server mutation. Network and Scheduling observations
remain paged at 100 Worker IDs; Result observation uses the public 1,000-ID
`results:load` boundary.

Inventory is produced by the same strict materializer as the fixed Correctness
and Convergence lanes. This lane supplies one 15,000-record Group
in 150 JSONL files. Materialization order defines the private topology: the
first 10,000 coordinates are retained and the final 5,000 are stopped. Public
evidence stores only counts and sorted worker-ID SHA-256 digests.

After all first-stage Tasks are approved and partially successful, the Harness
sends 50 one-shot Lab batch-stop requests of 100 coordinates. Any failed or
ambiguous request fails the mutation; the runner does not retry, compensate or
reshape the Fleet. Stopping a run does not delete its Server-issued identity.

The generated Host assembly contains only
`extension.worker.string.md5`. The Runtime Server uses the checked
`scenario-workers` Profile plus the Integration-owned capacity configuration.
Redis, Server and Host are separate processes. Across all three Server
restarts the Host PID is retained and no Lab start or Prepare endpoint is
invoked; existing Clients reconnect to the same Endpoint URI.

## Acceptance

The initial 15,000-Worker world uses one continuous stable window: at least
three complete scans at or above 14,800 connected-and-HOT and at least 60
seconds without a violating scan. A violation restarts the window. After
contraction and after each Server recovery, the active set requires three
scans at or above 9,900 connected-and-HOT. Every convergence scan also rejects
HOT-but-disconnected active Workers and any connected, HOT or missing stopped
Worker. The latter check keeps all 5,000 stopped baseline identities present in
Kernel scheduling truth even though the Lab snapshot no longer exposes a
stopped run's `workerId`.

Each loaded operation creates ten Tasks and appends 5,000 valid Items to each
in 50 requests. All ten are nonterminal when mutation occurs, with 1..25,000
successes and at least 25,000 unresolved Items. Missing that window fails rather
than reshaping the workload. The first successful Result must appear within
120 seconds; after progress starts, a global 90-second success gap outside the
intentional restart interval fails the lane. All Tasks must become terminal
within 900 seconds from approval. Each Task is exported exactly once, after
terminal observation, and its success-only message-ID set must equal its 5,000
submitted IDs. Hard restart stages additionally require backlog in the first
post-restart Result observation and later new progress. Result payload remains
opaque. Outside the planned Server-down/reconnect interval, only the 9,900
active connection threshold applies during work; the Harness first observes
connection recovery instead of treating readiness as immediate Fleet recovery.
HOT is re-established after drain.

The Worker Host and each Runtime Server process must remain below 512 native
Linux threads throughout. The Host remains below 32,768 open file descriptors
and each Server below 16,384. At stable checkpoints the Host is below 128
threads, each Server below 256, and both processes are below 15,512 FDs in the
15k world or 10,512 FDs in the retained 10k world. Three samples two seconds
apart establish each checkpoint. Final stable Host growth relative to the
first retained checkpoint is at most 128 FDs and 32 threads. RSS, cumulative
CPU time and interval average CPU core usage are recorded without cross-machine
limits. Every Server process has a separate resource label.

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
high-cost proof is intentionally outside the ordinary pull-request gate. It is
expected to finish in roughly 15-30 minutes and exits as soon as its four
stages pass; the 60-minute Workflow timeout is only a failure ceiling.
