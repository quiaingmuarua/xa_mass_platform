# xa-mass-testing

Status: current testing owner README.

`xa-mass-testing` owns perf, SDK embedded-runtime harnesses, and chaos probes.

For current test-layer truth, minimum verification, and CI gate truth, start
with [`../doc/TESTING_INDEX.md`](../doc/TESTING_INDEX.md). This README is
module-local only.

## Fast Intent

Use this module when the real risk lives in runtime edges or runtime pressure:

- disconnect / reconnect
- lease expiry / takeover / replay
- delayed retry visibility
- transport composition across polling / websocket / socket
- perf pressure on the scheduling mainline

Do not use this module as the first home for:

- ordinary worker/task matching correctness
- the full scheduling competition matrix
- projection-first lifecycle proof

Use this module when the question is:

- did a hot path get slower
- did SDK transport composition drift
- does the runtime recover under disconnect or lease-expiry churn

Do not use it as a replacement for Boot-shell E2E when the change also touches
HTTP/API shell behavior or Spring wiring.

What this module proves:

- hot-path perf signal
- SDK transport composition and transport-adapter runtime behavior
- degraded-condition and recovery behavior such as disconnect, lease expiry,
  late replay, and retry reset
- distributed edge behavior that is too wiring-heavy or timing-heavy to treat as authoritative proof through projection-first local tests
- distributed-readiness pressure around the scheduling mainline

What this module does not replace:

- engine-first scheduling correctness coverage
- Boot-shell E2E for host HTTP/mainline behavior
- engine deterministic concurrency tests for kernel-local invariants
- the repo-level testing map or CI truth

Agent shortcut:

- if the main question is "which worker/context should win", start in `xa-mass-engine`
- if the main question is "does this survive real host wiring", start in `xa-mass-server`
- if the main question is "does this survive disconnect, replay, or pressure", start here

Testing-policy note:

- use this module to prove disconnect, replay, lease-expiry, takeover, and other distributed edge behavior on the real runtime path
- do not use this module as the first home for ordinary worker/task matching correctness; that belongs in engine acceptance first
- do not add local projection-first tests in engine/transport when the real risk belongs here
- compatibility projection may still appear in reports or explicit audit/residue checks, but it is not the primary correctness surface for runtime convergence
- chaos smoke correctness assertions must be runtime/aggregate/trace first: `TaskWorkRuntime` stats, active leases, task terminal state, and `ExecutionEvent` transitions are the proof surface; compatibility message/attempt views are report payload only unless the runner is explicitly about projection residue
- transport load correctness assertions must use task terminal state, `TaskWorkRuntime` final counters, delivery diagnostics, and worker receive/result metrics; compatibility projection must not define transport-load pass/fail

## Runner Map

| Surface | Main runner / entry | Primary risk | Artifact |
| --- | --- | --- | --- |
| `perf` | `com.xa.mass.testing.perf.TaskFlowLoadModelRunner` | callback cost, progress recompute, release/refill cost, runtime backend selection, counter drift | `target/perf-reports/` |
| `perf smoke bundle` | `scripts/run-perf-smokes.sh` | current workspace perf smoke fast path for workload mix plus delayed interactive retry wakeup | `target/perf-reports/` |
| `perf smoke: workload mix` | `com.xa.mass.testing.perf.TaskWorkloadMixSmokeRunner` | interactive assignment latency under bulk background pressure; the runner reserves an interactive lane worker so the smoke measures lane isolation instead of bulk worker starvation | `target/perf-reports/` |
| `perf smoke: interactive retry wakeup` | `com.xa.mass.testing.perf.TaskInteractiveRetryWakeupSmokeRunner` | delayed interactive retry visibility and redispatch observability under bulk background pressure; the runner starts the runtime ready pump so delayed retry truth is consumed from `TaskWorkRuntime` rather than inferred from projection | `target/perf-reports/` |
| `SDK transport harness` | `scripts/run-sdk-transport-load.sh` | embedded runtime composition across polling / websocket / socket, with runtime-counter finality and source guardrails against projection-first pass/fail | `target/concurrency-reports/` |
| `polling scheduling soak` | `scripts/run-polling-scheduling-soak.sh` | manual/scheduled polling-worker pressure on engine scheduling; proves structured runtime invariants, configured success/failure and late-worker-join profiles, result sequential read, active lease drain, and canonical trace validate/stats | `target/soak-reports/`, `target/soak-traces/` |
| `polling scheduling fast soak` | `scripts/run-polling-scheduling-fast-soak.sh` | scheduled/manual polling soak profile with mixed results, late worker join, result sequential read, canonical trace validation, and optional trace analyzer proof; not a PR gate | `target/soak-reports/`, `target/soak-traces/` |
| `chaos: websocket disconnect/reconnect` | `com.xa.mass.testing.chaos.SdkWebSocketDisconnectChaosRunner` | worker disconnects inside an active lease, reconnects, submits the delayed result, and later receives follow-up work | `target/chaos-reports/` |
| `chaos: websocket lease-expiry redispatch` | `com.xa.mass.testing.chaos.SdkWebSocketLeaseExpiryRedispatchChaosRunner` | disconnect without result, watchdog expiry, retry reset, takeover by another websocket worker | `target/chaos-reports/`, `target/chaos-traces/` |
| `chaos: websocket late stale result after lease expiry` | `com.xa.mass.testing.chaos.SdkWebSocketLateResultAfterLeaseExpiryChaosRunner` | original worker disconnects, lease expires, takeover succeeds, then the stale worker reconnects and replays a late result that must not overwrite runtime finality | `target/chaos-reports/`, `target/chaos-traces/` |
| `chaos: polling lease-expiry redispatch` | `com.xa.mass.testing.chaos.SdkPollingLeaseExpiryRedispatchChaosRunner` | polling worker claims work, stalls without a result, goes offline, watchdog expiry resets runtime work, and another polling worker takes over to finish successfully | `target/chaos-reports/`, `target/chaos-traces/` |
| `chaos: polling all messages failed` | `com.xa.mass.testing.chaos.SdkPollingAllMessagesFailedChaosRunner` | polling worker always submits failure with no retries; all messages converge to FAILED and the task closes with ALL_MESSAGES_FAILED | `target/chaos-reports/` |
| `chaos: polling mixed results` | `com.xa.mass.testing.chaos.SdkPollingMixedResultsChaosRunner` | multi-message task where some messages succeed and some fail (driven by per-message `shouldFail` input flag); task closes with MIXED_MESSAGE_RESULTS | `target/chaos-reports/` |
| `chaos: polling message retry exhausted` | `com.xa.mass.testing.chaos.SdkPollingMessageRetryExhaustedChaosRunner` | polling worker always fails; each message has `maxRetryCount=2` and burns 3 total attempts before `RETRY_EXHAUSTED` finalization; task closes with ALL_MESSAGES_FAILED; `TASK_WORK_RETRY_RESET` events verified in trace | `target/chaos-reports/` |
| `chaos smoke bundle (CI gate)` | `scripts/run-chaos-smokes.sh` | fast CI gate for the three distributed-edge runtime recovery probes: polling lease-expiry redispatch, websocket lease-expiry redispatch, and websocket late stale result replay | `target/chaos-reports/` |

## Commands

When a runner depends on current `xa-mass-engine` / `xa-mass-sdk` changes, refresh sibling artifacts first:

```bash
./mvnw -pl xa-mass-testing -am -DskipTests install
```

Fastest current-workspace perf smoke bundle:

```bash
xa-mass-testing/scripts/run-perf-smokes.sh
```

This script first refreshes sibling module artifacts with `-pl xa-mass-testing -am -Dmaven.test.skip=true install`, then runs the smoke mains through a direct runtime classpath. Use it when `xa-mass-engine` changed in the current workspace and you want one reliable perf-smoke entrypoint without being blocked by unrelated test-compilation drift in sibling modules.

To include the task-flow runtime backend proof in the same script, opt in with
the backend list:

```bash
MASS_PERF_TASK_FLOW_BACKENDS=memory xa-mass-testing/scripts/run-perf-smokes.sh
MASS_PERF_TASK_FLOW_BACKENDS=memory,redis MASS_PERF_TASK_FLOW_REDIS_URI=redis://localhost:6379 xa-mass-testing/scripts/run-perf-smokes.sh
```

For the interactive retry wakeup smoke inside the bundle, the script also pins a more stable engine retry-delay JVM property by default:

- `xa.mass.engine.interactiveWorkRetryDelayMillis=200`
- `mass.retrywakeup.smoke.minRetryDispatchDelayMillis=80`

Override them with environment variables:

- `XA_MASS_INTERACTIVE_RETRY_DELAY_MILLIS`
- `MASS_RETRYWAKEUP_SMOKE_MIN_DELAY_MILLIS`

`scripts/run-perf-smokes.sh` also enforces that perf smoke runners stay runtime/timing-first. The smoke runners must not read compatibility message/attempt projection or message stats as their proof surface. Full load reports such as `TaskFlowLoadModelRunner` use `TaskWorkRuntime` final counters plus `TaskResultRuntime` stable-final result count for pass/fail; storage/projection metrics are not part of the perf smoke lane.

Current perf smoke modeling:

- workload mix uses a lane-aware matcher with one reserved interactive worker; bulk still creates background pressure, but it cannot consume every worker and turn the smoke into a starvation test
- workload mix uses a one-item interactive task because the smoke measures first-dispatch latency, not multi-round dispatch
- interactive retry wakeup starts `RuntimeReadyDispatchPump`; delayed retry visibility is therefore proven through runtime ready truth

Perf load model:

```bash
./mvnw -q -pl xa-mass-testing -am -DskipTests install
./mvnw -q -pl xa-mass-testing -Dexec.classpathScope=compile -Dmaven.test.skip=true org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.perf.TaskFlowLoadModelRunner -Dmass.load.runtimeBackend=memory
./mvnw -q -pl xa-mass-testing -Dexec.classpathScope=compile -Dmaven.test.skip=true org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.perf.TaskFlowLoadModelRunner -Dmass.load.runtimeBackend=redis -Dmass.load.redisUri=redis://localhost:6379
./mvnw -q -pl xa-mass-testing -Dexec.classpathScope=compile -Dmaven.test.skip=true org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.perf.TaskFlowLoadModelRunner -Dmass.load.runtimeBackend=memory -Dmass.load.duplicateResultEveryNth=3
```

`TaskFlowLoadModelRunner` is the shared runtime-selection proof for `TRS-D2`.
It uses explicit WorkerGroup selector truth and starts `RuntimeReadyDispatchPump`,
so BATCH refill is driven by `TaskWorkRuntime.readyTaskIds(...)`. The report
includes `runtimeProof.finalResultCount`, `duplicateDispatchItems`,
`duplicateResultItems`, `staleResultItems`, `expiredLeaseItems`,
`processingCounterDrift`, `resultCounterDrift`, `firstDispatchLagMillis`, and
`claimedMessagesPerSecond`. Duplicate-result proof is opt-in through
`mass.load.duplicateResultEveryNth` or
`MASS_PERF_TASK_FLOW_DUPLICATE_RESULT_EVERY_NTH`; it submits an extra result
callback after an accepted success and verifies stable-final convergence stays
bounded.
Redis runs use a safe default `xa:mass:perf:*` namespace and clean it before and
after the run; override with `mass.load.redisNamespace` when a retained namespace
is needed.

Mixed workload perf smoke:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.perf.TaskWorkloadMixSmokeRunner
```

Interactive retry wakeup perf smoke:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.perf.TaskInteractiveRetryWakeupSmokeRunner
```

SDK transport harness:

```bash
xa-mass-testing/scripts/run-sdk-transport-load.sh -Dmass.sdk.load.transport=polling -Dmass.sdk.load.workloadClass=BULK
xa-mass-testing/scripts/run-sdk-transport-load.sh -Dmass.sdk.load.transport=websocket -Dmass.sdk.load.workloadClass=INTERACTIVE
xa-mass-testing/scripts/run-sdk-transport-load.sh -Dmass.sdk.load.transport=socket -Dmass.sdk.load.workloadClass=BULK
```

`scripts/run-sdk-transport-load.sh` enforces the same source-level rule as the PR smoke scripts: the transport load runner must not import compatibility projection helpers, message/attempt projection models, projection-derived stats, or direct task-detail-store access. Its report uses `runtimeWork`, delivery diagnostics, and worker metrics as the correctness surface.

Polling scheduling soak:

```bash
xa-mass-testing/scripts/run-polling-scheduling-soak.sh -Dmass.soak.durationSeconds=120 -Dmass.soak.workerCount=16
xa-mass-testing/scripts/run-polling-scheduling-fast-soak.sh
```

The polling scheduling soak is a manual or scheduled lane, not a PR gate. It drives SDK polling workers through the engine scheduling mainline and writes report JSON plus canonical trace JSONL under `target/soak-reports/` and `target/soak-traces/`. Its pass/fail proof is runtime/aggregate/result/trace-first: task terminal state, `TaskWorkRuntime` counters, active lease drain, SDK result windows, worker metrics, `JsonlExecutionEventSink` drop count, and `xa-mass-trace` validation/stats. The report includes `proof.runtimeInvariants`, a structured issue list that identifies whether failure came from task terminal count, runtime work counters, visible results, active lease drain, trace validation/drop, late-worker participation, trace analyzer failure, or worker failures. The `proof` bundle also groups `resultSequentialRead`, `workerMetrics`, `workerLifecycle`, `deliveryDiagnostics`, `trace`, and `failureSamples` so scheduled runs have one stable diagnostic entry point. Set `-Dmass.soak.failureEveryNth=N` to run mixed-result or all-failed profiles; the runner verifies expected terminal reasons and success/failed runtime counters from that profile and, when trace is enabled, binds a representative sample task into the named `mixed-result-terminal-convergence` or `all-failed-terminal-convergence` analyzer. Set `-Dmass.soak.initialWorkerCount=N -Dmass.soak.lateWorkerStartAfterMillis=M -Dmass.soak.requireLateWorkerWork=true` to run a late-worker-join profile where only part of the polling fleet is online at the start; when trace is enabled, the runner records an actual late-worker task sample and runs the `late-worker-backfill` trace analyzer into `proof.trace.analyses`. See [`SOAK_TESTING_ROADMAP.md`](SOAK_TESTING_ROADMAP.md).

WebSocket disconnect chaos:

```bash
./mvnw -pl xa-mass-testing -am -Dexec.classpathScope=compile -Dmaven.test.skip=true compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.chaos.SdkWebSocketDisconnectChaosRunner
```

Lease-expiry redispatch chaos:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true -Dmass.sdk.chaos.taskMessageLeaseSeconds=2 -Dmass.sdk.chaos.timeoutSeconds=30 compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.chaos.SdkWebSocketLeaseExpiryRedispatchChaosRunner
```

Late stale result after lease expiry chaos:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true -Dmass.sdk.chaos.taskMessageLeaseSeconds=2 -Dmass.sdk.chaos.timeoutSeconds=30 compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.chaos.SdkWebSocketLateResultAfterLeaseExpiryChaosRunner
```

All messages failed chaos:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.chaos.SdkPollingAllMessagesFailedChaosRunner
```

Mixed results chaos:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.chaos.SdkPollingMixedResultsChaosRunner
```

Polling lease-expiry redispatch chaos:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true -Dmass.sdk.chaos.taskMessageLeaseSeconds=2 -Dmass.sdk.chaos.timeoutSeconds=30 compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.chaos.SdkPollingLeaseExpiryRedispatchChaosRunner
```

Per-message retry exhaustion chaos:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.chaos.SdkPollingMessageRetryExhaustedChaosRunner
```

Chaos smoke bundle (three PR-gated distributed-edge scenario ids):

```bash
xa-mass-testing/scripts/run-chaos-smokes.sh
```

Direct scenario-id entrypoint for a single existing probe:

```bash
./mvnw -pl xa-mass-testing -am -Dexec.classpathScope=compile -Dmaven.test.skip=true compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.workerfault.WorkerFaultScenarioCli -Dexec.args=polling-lease-expiry-redispatch
```

## Reading Rule

Start from the runner class, then read the matching report artifact.

Look at these first:

- perf: `wallClock.totalMillis`, release cost, runtime work counters
- SDK transport harness: `runtime.transport`, `tasks.terminalReasons`, `workerMetrics`
- chaos: `task.runtime` counters, active lease count, task terminal reason, trace `byType`, then worker online/offline transitions
- chaos report field `task.compatibilityProjection` is residue/report context; do not treat it as the runner's primary correctness proof

Use repo-level docs only for system-level policy:

- [../doc/TESTING_INDEX.md](../doc/TESTING_INDEX.md)
- [../doc/TESTING_BASELINE.md](../doc/TESTING_BASELINE.md)
- [../doc/VERIFIED_RUNBOOK.md](../doc/VERIFIED_RUNBOOK.md)
- [../transport/AGENTS.md](../transport/AGENTS.md)

## Current Chaos Focus

Current PR-gated chaos probes cover three distributed-edge scenario branches:

- disconnect without a result, let lease expiry trigger redispatch, and finish on a different worker
- disconnect without a result, let lease expiry trigger redispatch and terminal success, then reconnect the original worker and replay a stale late result that must not mutate the already-final logical message
- polling worker claims work, stalls without a result, goes offline, and a second polling worker takes over after lease expiry

Additional scheduled/manual chaos probes cover support scenario branches:

- disconnect, reconnect, then submit the delayed result on the original worker
- all messages fail with no retries; runtime counters finalize all work as failed and task closes with `ALL_MESSAGES_FAILED`
- multi-message task with partial success and partial failure; runtime counters prove the split and task closes with `MIXED_MESSAGE_RESULTS`
- per-message retry budget exhaustion (`maxRetryCount=2`, 3 total attempts per message); runtime counters finalize all work as failed, task closes with `ALL_MESSAGES_FAILED`, and `TASK_WORK_RETRY_RESET` events are verified in trace

The PR-gated chaos probes are the three distributed-edge runtime recovery paths: polling lease-expiry redispatch, websocket lease-expiry redispatch, and websocket late-result replay. Their main assertions are runtime/aggregate/trace-first; compatibility projection is kept in reports only for bounded diagnosis. Result-shape probes such as all-failed, mixed-results, and retry-exhausted remain scheduled/manual support because their primary proof lives in the engine/server/trace convergence chain. Websocket disconnect/reconnect also remains a useful manual probe until it is reduced to one crisp mechanism invariant instead of a mixed behavior bundle. All chaos probes still write report JSON under `target/chaos-reports/`.

`scripts/run-chaos-smokes.sh` enforces this source-level rule before running the probes: PR-gated chaos smoke runners must not import or call compatibility projection helpers or projection-derived stats such as `ProjectionTestViews`, `CompatibilityMessageView`, `CompatibilityAttemptView`, `TaskMessageStats`, `TaskMessageAttemptStats`, `getTaskMessage*`, `waitForSingleMessage`, or direct `taskDetailStore()` access. Report/audit helpers may still live under `chaos.support`, and non-gated runners may keep explicit diagnostic reads until they are promoted to the PR gate.

These probes stay at the SDK embedded-runtime layer. Matching Boot-shell HTTP behavior should be verified separately under `xa-mass-server` E2E.
