# Worker Loaded Capacity + Recovery Stability

`integrations:worker-loaded-recovery` owns the local nightly/manual recovery proof
and the published kit's external capacity consumer. Both use one Java 21 Worker
Simulator process, one `JavaWorkerManager`, one WorkerGroup and one WebSocket
Adapter Endpoint, but their evidence and process authority are separate:

| Mode | Runtime and lifecycle | Work and claim |
| --- | --- | --- |
| `local-proof` | Runner owns Redis, Server and Host, including planned Server faults | Existing four 50,000-Item recovery stages and process stability |
| `external-release` | Runner owns only its local Host and Harness; connects to an explicitly supplied published Runtime | Separate 15k and retained-10k connection/work observations, 50,000 Items at each scale; no Server recovery claim |

The following local proof remains unchanged. The [external consumer](#external-release-capacity)
is a distinct manual deployment acceptance, not another ordinary PR requirement.

```text
15,000 prepared Worker identities
-> at least 14,800 connected with scheduling identities present
-> begin ten fully seeded Tasks x 5,000 Items
-> deterministically stop 5,000 runs while work is active
-> retain the exact 15,000 identities and exact 10,000 active target set
-> finish and export the original 50,000 Items; retain connections and identities
-> repeat a 50,000-Item operation across graceful Server restart
-> repeat across hard Server restart
-> repeat across a second hard Server restart
-> same 10,000 active identities recover without start or Prepare
-> 40 terminal Tasks and 200,000 exact success-only exports
```

The ten Tasks share one WorkerGroup, empty allocation rules and the same
candidate pool through the explicitly supplied Any Pool (`any / {} / 1000` per Task,
merged as shared MAX demand). All ten are fully
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
external platform proof fixture plus the Integration-owned capacity configuration.
Redis, Server and Host are separate processes. Across all three Server
restarts the Host PID is retained and no Lab start or Prepare endpoint is
invoked; existing Clients reconnect to the same Endpoint URI.

## Acceptance

The initial 15,000-Worker world uses one continuous stable window: at least
three complete scans at or above 14,800 connected and at least 60
seconds without a violating scan. A violation restarts the window. After
contraction, before subsequent work and after each workload drain, the active set
requires three scans at or above 9,900 connected. Every scan also rejects missing
active/stopped scheduling identities and any connected stopped Worker. All 5,000
stopped baseline identities must remain present in Kernel scheduling truth even
though the Lab snapshot no longer exposes a stopped run's `workerId`.

These checkpoints have no RUNNING Task. Scheduling polarity, including active
RECOVERY or disconnected HOT, is diagnostic there. Serviceability derives its
Group roots from RUNNING Tasks; without a root it does not probe the Group.
Connection readiness does not establish execution recovery: each subsequent
real ten-Task workload must still make progress, reach terminal state and export
its exact submitted identities. No idle RPC/anchor Task, direct Score mutation,
or extra device start/Prepare is added to manufacture HOT evidence. The initial
60-second hold, three-scan gates, connection thresholds and all workload/resource
deadlines remain unchanged.

Each projection scan also records `activeNotHotStates`: counts of active
Workers outside HOT, keyed by `scheduling|network` projection (for example
`recovery|connected`), without Worker identities. The stage summary repeats the
final post-work value as `postWorkActiveNotHotStates`. Connected-and-HOT, stopped
HOT and HOT-without-connection counts remain in the timeline for diagnosis; they
are not idle acceptance gates. `initialHeadroomConnected`, `preWorkConnected`
and `postWorkConnected` are the connection witnesses, with qualifying scan/hold
evidence. The Python runner independently validates these fields and exact workload
exports; it does not accept a historical connected-and-HOT field as a substitute.
The [Pacer assembly](../../kernel_pacer_jvm/doc/application-assembly.md) owns the
RUNNING-root contract. Historical reports retain their original conclusions;
the [2026-10-21 nightly](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/39473549333)
at `23376faf5` completed two 50,000-Item workloads but failed
the former idle HOT gate with 280 connected RECOVERY Workers. This correction
changes the proof claim, not Pacer behavior or that recorded run's failed status.

Each loaded workload creates ten Tasks and appends 5,000 valid Items to each
in 50 requests. A complete active-network scan establishes the required
connection baseline immediately before approval; subsequent scans retain their
normal interval from that observation. This keeps the initial Fleet scan outside
the finite mutation window while retaining its threshold and sampled minimum.
All ten are nonterminal when mutation occurs, with 1..25,000
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
After drain, connection/identity stability is checked; fleet-wide HOT without
RUNNING Task roots is not claimed.

The Worker Host and each Runtime Server process must remain below 512 native
Linux threads throughout. The Host remains below 32,768 open file descriptors
and each Server below 16,384. At stable checkpoints the Host is below 128
threads, each Server below 256, and both processes are below 15,512 FDs in the
15k world or 10,512 FDs in the retained 10k world. Three samples two seconds
apart establish each checkpoint. Final stable Host growth relative to the
first retained checkpoint is at most 128 FDs and 32 threads. RSS, cumulative
CPU time and interval average CPU core usage are recorded without cross-machine
limits. Every Server process has a separate resource label.

Resource sampling is a required oracle, independent of the loaded-work and
recovery results. The background sampler targets five-second intervals, with
a fixed 15-second maximum gap between completed periodic cycles (including
startup and the final tail). Each process must have periodic samples; checkpoint
or final samples cannot substitute for them. The JSONL records distinguish
`periodic`, `checkpoint` and `final` samples. `resourceSampling` in the final
summary records cycle count, first/last timing, maximum gap, per-process sample
counts, confirmed exit races and any first failure.

A `/proc` read may race a planned Server exit, including `PermissionError` while
the process is dying. The sampler makes one bounded exit check of at most one
second. Only a confirmed exit permits skipping that measurement; a still-live
process's read error, invalid data or evidence-write error makes sampling fail.
Failure is sticky, as is an unexpectedly stopped sampler or an excessive gap.
The runner checks sampler health during HTTP, gate and Harness/process waits;
later successful checkpoints cannot restore the resource claim. Final sampling
is stopped and joined before a passing summary is published. Sampling failure
writes `status=failed`, `failureKind=resource-sampling-failed` and incomplete
coverage diagnostics, then exits nonzero; it does not publish partial maxima as
a passed resource-stability result. Cleanup does not take additional samples.

Runner tests cover live-process read failures, confirmed exit races, evidence
write failures, stalled writers and finalization races. On unprivileged Linux,
a real child process disables dumpability after valid `/proc` samples; the test
requires the resulting permission loss to interrupt the main Harness wait and
produce failed evidence while the child is still alive.

Run quick contracts on any development host:

```powershell
.\gradlew.bat :integrations:worker-loaded-recovery:test
python -m unittest discover `
  -s integrations/worker-loaded-recovery `
  -p "test_*.py"
```

The real proof requires Linux, Java 21, Redis 7.4, `nofile >= 65536` and a
local ephemeral port range containing at least 45,000 ports. Install the small
shared Python dependency set, widen the disposable host's port range, then run:

```bash
python -m pip install -r .github/scripts/requirements.txt
sudo sysctl -w net.ipv4.ip_local_port_range="10000 65535"
python integrations/worker-loaded-recovery/run_worker_loaded_recovery.py \
  --prepared-workers 15000 \
  --retained-workers 10000 \
  --minimum-initial-connected 14800 \
  --minimum-retained-connected 9900 \
  --workload-items-per-task 5000 \
  --redis-url redis://127.0.0.1:6379/15 \
  --output-root build/worker-loaded-recovery-proof
```

The same command runs in `.github/workflows/worker-loaded-recovery.yml`; this
high-cost proof is intentionally outside the ordinary pull-request gate. It is
expected to finish in roughly 15-30 minutes and exits as soon as its four
stages pass; the 60-minute Workflow timeout is only a failure ceiling.

## External Release capacity

The same Integration supplies the compiled `WorkerExternalCapacityMain` Harness
and `run_external_loaded.py`, shipped as `acceptance/run_loaded.py` in the
[Worker Simulator kit](../../distribution/worker-simulator/README.md). Kit manifest
schema 2 records this third entry. Consume the published kit from the same Release
as the target Runtime; do not run Gradle, build source or use a checkout fallback
on the external consumer. It requires Linux, Java 21 and standard-library Python
3.11+, `nofile >= 65536` and at least 45,000 local ephemeral ports.

```sh
python3 acceptance/run_loaded.py \
  --runtime-url https://console.example.com \
  --worker-runtime-url https://workers.example.com \
  --project your-project --group your-java-workers \
  --endpoint-manager runtime-websocket \
  --access-key-file /private/operator-access-key.txt \
  --gateway-credential-file /private/worker-access.json \
  --output-dir /data/loaded-run-001
```

Both URLs are explicit HTTPS origins. The caller supplies a Project-granted
operator access key and a Worker gateway credential, never an administrator key
or SSH access. The target operator prepares the Group, MD5 event, Any Pool/query
and gateway admission before the run. The consumer neither registers resources
nor updates Server configuration. Before starting Workers, it point-reads the
requested Group's registered MD5 event, `any` / `worker.any` relationship and
Project association; it does not discover other Groups. Give its Group/inventory coordinates one active
Host owner; a different local directory is not a new identity namespace. This is
an operator handoff precondition, not an exclusive-ownership claim established by
the consumer's local inventory or a bounded Worker preview.

The full run fixes the workload and observation defaults:

1. Prepare 15,000 identities and establish at least 14,800 stable connections.
2. Create ten fully populated finite Tasks with 5,000 Items each, approve them,
   observe completion and verify their exact successful Results and full exports.
3. After this workload drains, stop the final 5,000 local runs once. Retain the
   same Host process, all prepared identities and the exact first 10,000 active set.
4. Establish at least 9,900 retained connections and run another ten Tasks of
   5,000 Items. Record the 15k and 10k measurements separately.

Each pre/post-work stable gate requires three qualifying scans and 60 seconds
without a violating scan. Missing scheduling identities or connected stopped
Workers fail qualification; the active connection minimum also applies to the
in-work scans. Requests have at most eight in flight, and individual Worker
starts are spaced at least 20 ms apart. Work retains the 120-second first-success,
90-second no-progress and 900-second terminal deadlines.

Both scales use `any / {} / 1000` shared supply and `worker.any`, not targeted
Worker selection. Results are checked against independently calculated MD5
answers as well as message identities and terminal Item/Task states. Idle
scheduling polarity remains diagnostic; no auxiliary Task or direct Score update
manufactures HOT evidence. No Server stop/restart, Redis access/cleanup or SSH is
available in this entry. The original local four-stage proof is not replaced by
these two external workloads.

Each successful case reports `elapsedMillis` from immediately before the first
approval until all ten Tasks are observed terminal, and `observedItemsPerSecond`
as independently verified Item count divided by that interval. Preloading, final
Item checks and complete export verification are outside the interval. These
measurements include approval, public network, Worker execution and observation
lag. They do not measure Server maximum TPS, Handler concurrency, per-Worker work
distribution or the claim that every connected Worker executed. A failed or
unconfirmed operation retains its known identities and receipts; it is not
automatically replayed. On failure the Harness point-checks only Tasks whose
creation it confirmed, and makes one close attempt for each still-nonterminal
Task, recording confirmation or uncertainty. It neither discovers other Tasks
nor deletes data or retries an uncertain close. Do not reduce the workload to turn a
failed full run into a capacity success.

`preloadMillis`, `verificationMillis` and its `exportMillis` subset remain separate
from the completion interval. Per-Task `terminalObservedMillis` uses the same
first-approval origin, with final counts and independently verified totals;
`phaseDurationsMillis` describes whole-run phase costs. Fixed inputs include ASCII
and Unicode. These fields explain where time was observed rather than attributing
all delay to the Runtime.

Cancellation, a sampler failure or the outer timeout can terminate the Harness
before its normal failure cleanup runs. Stopping the local processes does not
prove remote Tasks were closed. The wrapper records remote cleanup as unconfirmed
and retains pre-send receipts and confirmed Task identities for explicit operator
follow-up; it does not add a competing shutdown-time mutation path.

`--diagnostic` permits reduced fixture counts/timings and an explicitly trusted
test CA. Its successful result is `diagnostic-passed`, with claim
`diagnostic-only`, never the 15k/10k capacity claim. Runtime Distribution runs
this reduced entry against its extracted local Runtime, real Redis and TLS
gateway to prove the shipped path. Normal HTTPS/WSS certificate and hostname
verification remain enabled in both modes.

Python samples only the consumer's Host and Harness through `/proc`, using the
existing fail-closed sampler. It stops its own processes and removes its temporary
credential copies; remote Tasks remain subject to normal retention. The output
directory must be new and outside the immutable kit. Public artifacts are
`summary.json`, `process-resources.jsonl`, and Java `evidence/summary.json`,
`evidence/timeline.jsonl` and `evidence/receipts.jsonl`. Child logs, inventory,
private inputs and `evidence/private/` identities are not public artifacts. Server/Redis/Nginx resource
observations, if requested, belong to a separate environment-operator witness;
the consumer cannot infer them from its own process samples.

### Workflow selection and deployment boundary

The existing `worker-loaded-recovery.yml` defaults to `local-proof`, including
scheduled runs. `external-release` is available only through a manual dispatch
on `main`, using the protected `release-preview-load` Environment. It downloads
and verifies the installed Release's immutable kit; it does not build the kit or
receive Server administrator credentials. Environment coordinates and private
inputs are documented in [TESTING](../../TESTING.md#external-release-load).

The version-scoped [4.2 Release contract](../../distribution/server/RELEASE-4.2.md)
owns the target resource budget, installed-version checks and host operation
record. Ordinary code changes continue to use local/CI proofs without GCP access.
An implemented consumer or passing reduced fixture is not evidence that a public
15k/10k campaign has run; only that campaign's versioned result establishes it.

## Project fixture

Use the shared [`scenario-workers` Project fixture](../../TESTING.md#worker-project-fixture).

### Platform configuration source

Current runs load `../worker_proof_support/platform-runtime.yaml` explicitly,
before this lane's overlay/CLI flags. It preserves the test Project, Group and
Adapter identities and existing budgets without activating a business profile.
The fixture is not packaged in the production Server. Historical benchmark
checkouts retain their version-specific configuration inputs.
