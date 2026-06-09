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
- chaos smoke correctness assertions must be runtime/aggregate/trace first: `TaskWorkRuntime` stats, active leases, task terminal state, and `ExecutionEvent` transitions are the proof surface; compatibility message/attempt views are report payload only unless the runner is explicitly about legacy projection audit
- transport load correctness assertions must use task terminal state, `TaskWorkRuntime` final counters, delivery diagnostics, and worker receive/result metrics; compatibility projection must not define transport-load pass/fail

## Proof Class Map

The repo-level proof classes are defined in
[`../doc/TESTING_INDEX.md`](../doc/TESTING_INDEX.md). This module contributes
to them as follows:

| Question | Proof class | This module's role |
| --- | --- | --- |
| Can it be used? | `Product / API Capability Proof` | `platform confidence smoke`, `server default startup smoke`, cross-process launchers, admin CLI, Java SDK task producer, Java SDK or worker API worker process, task creation, worker execution, and result reads through supported external surfaces. These prove product/API usability for named credential/session families, not full policy correctness. |
| Can it be wrong? | `Policy & Safety Correctness Proof` | Scheduling/policy correctness guards, negative auth checks, proof-registry guards, profile-matrix guards, trace analyzer pairing, and representative reports that show the platform fails closed or refuses unsafe mutation. Primary scheduling/policy correctness is engine deterministic proof first; server E2E is representative real-wiring proof only. This class has higher confidence priority than happy-path or load wording, but it must not grow low-value server E2E matrices. |
| Can it withstand this exact condition? | `Scoped Operational Resilience Proof` | scale/contention evidence, fault/recovery evidence, chaos, perf, soak, transport load, runtime restart, worker churn, lease-expiry, stale replay, retry, Redis-backed runtime recovery, and scheduled/manual pressure evidence, scoped to the exact scenario, fault/load, duration, and pass/fail oracle. Fast stable cases can be PR-gated; expensive or noisy cases stay scheduled/manual until calibrated. |

Proof lines used by this module:

- `operator-admin-session`: admin CLI login/env init, project/rule/API-key setup,
  task seal/approve, and operator commands with a valid operator session that
  must not be wrongly rejected.
- `task-producer-api-key`: create task, append items, and read allowed
  task/result/archive data through Java SDK task producer paths with a valid
  task API key that must not be wrongly rejected.
- `worker-api-key`: worker registration/topology, online/heartbeat/poll, result
  submit, command ack, state report, and capability report through worker paths
  with a valid worker API key that must not be wrongly rejected.
- `scheduling-policy-correctness`: engine-first selection/admission/gating
  proof, with server E2E only as representative real wiring.
- `lifecycle-result-correctness`: lifecycle transition, retry/finality,
  resource release, result convergence, duplicate callback, and stale callback
  correctness.
- `authorization-no-bypass-safety`: negative credential, scope, route-family,
  CSRF, fixture-header, and impersonation cases.
- `scale-contention-evidence`: named load/contention/capacity scenario with
  explicit pass/fail oracle.
- `fault-recovery-evidence`: named restart/reconnect/lease/stale/duplicate/fault
  scenario with explicit pass/fail oracle.

Correct credential/session plus correct route family, scope, project/event, and
request shape must be treated as an authorized-positive capability proof. If it
is rejected by auth, CSRF, an interceptor, route mapping, or credential-family
handling, the failure belongs to `Product / API Capability Proof`. Negative
wrong-credential/scope/route/CSRF/fixture/impersonation checks belong to
`authorization-no-bypass-safety`.

End-to-end is recorded as `evidenceShape`, not as a proof class. The proof
summary writer emits `proofClass`, `proofLines`, `proofQuestion`,
`evidenceRole`, `evidenceShape`, `gateType`, `credentialRouteFamilies`,
`authorizedPositiveChecks`, `credentialChecks`, and `claimScope` per evidence
item so artifacts can distinguish a product/API capability smoke from a
policy/safety proof, a scoped operational resilience report, a source/schema
guard, or downgraded artifact metadata.

Proof summary counts are role-aware:

- `runtime-proof`, `deterministic-proof`, and `integrated-proof` evidence may
  increase `proofClassCounts` and `proofLineCounts`.
- `source-guard`, `schema-guard`, and `release-policy-guard` evidence protects
  proof shape but counts under `guardCounts` / `guardProofLineCounts`.
- `artifact-metadata` keeps incomplete or downgraded reports visible without
  adding proof line counts.
- Operation-level `authorizedPositiveChecks` and `credentialChecks` totals count
  only executed checks with `passed` or `failed` status. `not-run` and
  `not-confirmed` checks remain in the source artifact but do not increase
  operation-level counts.

The bounded no-bypass matrix lives in
[`proof/authorization-no-bypass-matrix.json`](proof/authorization-no-bypass-matrix.json).
Rows owned by platform confidence must be emitted as structured
`credentialChecks`; rows owned by API contract health are linked, not duplicated
into the platform confidence smoke.

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
| `platform confidence smoke` | `scripts/run-platform-confidence-smoke.sh` | packaged server process plus admin CLI env init plus Java SDK worker/task launchers; proves server startup, session operator auth, catalog/rule/API-key preparation, worker registration, task submission, dispatch, and visible result through real process boundaries | `target/platform-confidence/` |
| `server default startup smoke` | `scripts/run-server-default-startup-smoke.sh` | packaged server jar no-arg startup, default `durable-local` profile/path, process liveness after health, operator seed/login readiness, and same SQLite file restart | `target/server-default-startup/` |
| `proof summary writer` | `scripts/write-proof-summary.mjs` | CI evidence summary that reads surefire XML plus lane-local JSON reports; keeps proof class, proof line, credential/route families, evidence shape, gate type, profiles, analyzers, and known non-proof boundaries visible in artifacts | `target/proof-summary/` |
| `chaos: websocket disconnect/reconnect` | `com.xa.mass.testing.chaos.SdkWebSocketDisconnectChaosRunner` | worker disconnects inside an active lease, reconnects, submits the delayed result, and later receives follow-up work | `target/chaos-reports/` |
| `chaos: websocket lease-expiry redispatch` | `com.xa.mass.testing.chaos.SdkWebSocketLeaseExpiryRedispatchChaosRunner` | disconnect without result, watchdog expiry, retry reset, takeover by another websocket worker | `target/chaos-reports/`, `target/chaos-traces/` |
| `chaos: websocket late stale result after lease expiry` | `com.xa.mass.testing.chaos.SdkWebSocketLateResultAfterLeaseExpiryChaosRunner` | original worker disconnects, lease expires, takeover succeeds, then the stale worker reconnects and replays a late result that must not overwrite runtime finality | `target/chaos-reports/`, `target/chaos-traces/` |
| `chaos: polling lease-expiry redispatch` | `com.xa.mass.testing.chaos.SdkPollingLeaseExpiryRedispatchChaosRunner` | polling worker claims work, stalls without a result, goes offline, watchdog expiry resets runtime work, and another polling worker takes over to finish successfully | `target/chaos-reports/`, `target/chaos-traces/` |
| `chaos: polling Redis runtime restart recovery` | `com.xa.mass.testing.chaos.SdkPollingRedisRestartRecoveryChaosRunner` | Redis-backed polling runtime owner is rebuilt with the same Redis namespace during active leased work, then another polling worker takes over after lease expiry | `target/chaos-reports/`, `target/chaos-traces/` |
| `chaos: polling all messages failed` | `com.xa.mass.testing.chaos.SdkPollingAllMessagesFailedChaosRunner` | polling worker always submits failure with no retries; all messages converge to FAILED and the task closes with ALL_MESSAGES_FAILED | `target/chaos-reports/` |
| `chaos: polling mixed results` | `com.xa.mass.testing.chaos.SdkPollingMixedResultsChaosRunner` | multi-message task where some messages succeed and some fail (driven by per-message `shouldFail` input flag); task closes with MIXED_MESSAGE_RESULTS | `target/chaos-reports/` |
| `chaos: polling message retry exhausted` | `com.xa.mass.testing.chaos.SdkPollingMessageRetryExhaustedChaosRunner` | polling worker always fails; each message has `maxRetryCount=2` and burns 3 total attempts before `RETRY_EXHAUSTED` finalization; task closes with ALL_MESSAGES_FAILED; `TASK_WORK_RETRY_RESET` events verified in trace | `target/chaos-reports/` |
| `chaos smoke bundle (CI gate)` | `scripts/run-chaos-smokes.sh` | fast scenario-id CI gate for the three distributed-edge runtime recovery probes: polling lease-expiry redispatch, websocket lease-expiry redispatch, and websocket late stale result replay | `target/chaos-reports/` |

## Commands

When a runner depends on current `xa-mass-engine` / `xa-mass-embedded-sdk` changes, refresh sibling artifacts first:

```bash
./mvnw -pl xa-mass-testing -am -DskipTests install
```

Fastest current-workspace perf smoke bundle:

```bash
xa-mass-testing/scripts/run-perf-smokes.sh
```

This script first refreshes sibling module artifacts with `-pl xa-mass-testing -am -DskipTests install`, then runs the smoke mains through a direct runtime classpath. Use it when `xa-mass-engine` changed in the current workspace and you want one reliable perf-smoke entrypoint without being blocked by unrelated test-compilation drift in sibling modules.

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

`scripts/run-perf-smokes.sh` also enforces that perf smoke runners stay runtime/timing-first. The smoke runners must not read review rows, projection-derived stats, or storage metrics as their proof surface. Full load reports such as `TaskFlowLoadModelRunner` use `TaskWorkRuntime` final counters plus `TaskResultRuntime` stable-final result count for pass/fail.

Current perf smoke modeling:

- workload mix uses a lane-aware matcher with one reserved interactive worker; bulk still creates background pressure, but it cannot consume every worker and turn the smoke into a starvation test
- workload mix reads project support from WorkerGroup capability truth, not from worker declaration residue
- workload mix uses a one-item interactive task because the smoke measures first-dispatch latency, not multi-round dispatch
- `scripts/run-perf-smokes.sh` defaults the workload-mix runner to `workload-mix-slow-bulk-interactive-isolation`; set `MASS_WORKLOAD_SMOKE_SCENARIO_ID` or `-Dmass.workload.smoke.scenarioId=...` to override it deliberately. The runner writes `workerProfile=SLOW_BULK` and `faultShape=slow-bulk-interactive-isolation` in the report.
- interactive retry wakeup starts `RuntimeReadyDispatchPump`; delayed retry visibility is therefore proven through runtime ready truth

Perf load model:

```bash
./mvnw -q -pl xa-mass-testing -am -DskipTests install
./mvnw -q -pl xa-mass-testing -Dexec.classpathScope=compile -Dmaven.test.skip=true org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.perf.TaskFlowLoadModelRunner -Dmass.load.runtimeBackend=memory
./mvnw -q -pl xa-mass-testing -Dexec.classpathScope=compile -Dmaven.test.skip=true org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.perf.TaskFlowLoadModelRunner -Dmass.load.runtimeBackend=redis -Dmass.load.redisUri=redis://localhost:6379
./mvnw -q -pl xa-mass-testing -Dexec.classpathScope=compile -Dmaven.test.skip=true org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.perf.TaskFlowLoadModelRunner -Dmass.load.runtimeBackend=memory -Dmass.load.expireFirstAttemptEveryNth=5 -Dmass.load.staleResultEveryNth=7 -Dmass.load.duplicateResultEveryNth=3 -Dmass.load.duplicateWakeupsOnApprove=4
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
bounded. Duplicate-wakeup proof is opt-in through
`mass.load.duplicateWakeupsOnApprove` or
`MASS_PERF_TASK_FLOW_DUPLICATE_WAKEUPS_ON_APPROVE`; it submits extra assignment
wakeups after approval and requires no duplicate runtime dispatch claims when
retry faults are disabled. Lease-expiry/refill proof is opt-in through
`mass.load.expireFirstAttemptEveryNth` or
`MASS_PERF_TASK_FLOW_EXPIRE_FIRST_ATTEMPT_EVERY_NTH`; it expires selected
first-attempt leases through `TaskLeaseMaintenancePort` and requires the
normal retry/refill path to converge. Stale-result proof is opt-in through
`mass.load.staleResultEveryNth` or `MASS_PERF_TASK_FLOW_STALE_RESULT_EVERY_NTH`;
it submits a wrong-token runtime result before the real callback and requires
the runtime stale counter to move without changing final convergence.
Redis runs use a safe default `xa:mass:perf:*` namespace and clean it before and
after the run; override with `mass.load.redisNamespace` when a retained namespace
is needed.

Mixed workload perf smoke:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.perf.TaskWorkloadMixSmokeRunner
```

Slow-bulk workload mix scenario row:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true -Dmass.workload.smoke.scenarioId=workload-mix-slow-bulk-interactive-isolation compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.perf.TaskWorkloadMixSmokeRunner
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

`scripts/run-sdk-transport-load.sh` enforces the same source-level rule as the PR smoke scripts: the transport load runner must not import review/projection helpers, projection-derived stats, or direct detail-store access. Its report uses `runtimeWork`, delivery diagnostics, and worker metrics as the correctness surface. Set `-Dmass.sdk.load.scenarioId=sdk-transport-load-polling`, `sdk-transport-load-websocket`, or `sdk-transport-load-socket` to select a current mode-specific delivery-diagnostics row inside the runner. The aggregate `sdk-transport-load` row still keeps top-level `transport=multi` and records the concrete run mode as `actualTransport`; mode-specific rows write the concrete top-level `transport` axis. These mode-specific rows are current transport diagnostics, not transport churn proof. Set `-Dmass.sdk.load.scenarioId=sdk-transport-load-websocket-churn` to run the current scheduled/manual WebSocket churn row; it performs a real WebSocket close/reconnect and records `workerMetrics.transportChurnDisconnects` / `workerMetrics.transportChurnReconnects`.

WebSocket transport churn row:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true -Dmass.sdk.load.forceExit=false -Dmass.sdk.load.scenarioId=sdk-transport-load-websocket-churn -Dmass.sdk.load.tasks=1 -Dmass.sdk.load.messagesPerTask=1 -Dmass.sdk.load.workers=1 -Dmass.sdk.load.batchSize=1 -Dmass.sdk.load.workerProcessingThreads=1 -Dmass.sdk.load.processingDelayMillis=20 -Dmass.sdk.load.timeoutSeconds=25 compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.concurrency.SdkTransportLoadRunner
```

Polling scheduling soak:

```bash
xa-mass-testing/scripts/run-polling-scheduling-soak.sh -Dmass.soak.durationSeconds=120 -Dmass.soak.workerCount=16
xa-mass-testing/scripts/run-polling-scheduling-fast-soak.sh
```

`scripts/run-polling-scheduling-fast-soak.sh` defaults to the stable
`polling-soak-noisy-mixed-result` scenario row through
`MASS_SOAK_SCENARIO_ID`, so scheduled reports have a comparable scenario id
instead of relying on implicit runner defaults.

Platform confidence smoke:

```bash
MASS_OPERATOR_PASSWORD=ops-admin xa-mass-testing/scripts/run-platform-confidence-smoke.sh --profile memory-local
MASS_OPERATOR_PASSWORD=ops-admin xa-mass-testing/scripts/run-platform-confidence-smoke.sh --profile durable-local
```

The confidence lane is a packaged-process gate, not a unit test. It starts the
real server jar with session operator auth, disables fixture-header auth,
enables the minimal operator credential seed, asserts `/api/v1/auth/config`
reports `authMode=session`, `sessionCookieSupported=true`, and
`operatorHeaderSupported=false`, runs `xa-mass-admin env init`, performs
representative fail-closed checks for unauthenticated operator, invalid task
API-key, and invalid worker API-key credentials, starts the Java SDK polling
worker launcher as a background process, runs the Java SDK task launcher to
create and append work, executes the operator `APPROVE` command through
`xa-mass-admin task command`, then waits for visible success through the Java
SDK result verifier. It writes categorized logs and `summary.json` under
`target/platform-confidence/`. The script may use `curl` for health and the
negative credential probes; positive catalog/rule/API-key/task/worker business
calls stay inside admin CLI or Java SDK launchers. Negative probes assert the
standard response envelope `code` and `msg`, and record `failureReason` in
`credentialChecks`; they are representative credential-family checks, not a
full route-permission matrix. The proof summary marks those checks with
`authorization-no-bypass-safety` while keeping the overall smoke scoped to
product/API capability. The positive admin/task/worker paths are
authorized-positive capability proof for their named credential/session family;
the summary records them as `authorizedPositiveChecks` with operation names such
as `operator.login`, `taskProducer.createAndAppendItems`,
`taskProducer.readResult`, `worker.registerAndPoll`, and `worker.submitResult`.

Server default startup smoke:

```bash
MASS_OPERATOR_PASSWORD=ops-admin xa-mass-testing/scripts/run-server-default-startup-smoke.sh
```

This smoke is separate from active-profile API/auth confidence. It packages the
server and admin CLI, starts the server jar with no application arguments from
an isolated working directory, observes the default `durable-local` path, waits
for health, keeps the process alive long enough to catch post-Tomcat startup
failures, checks logs for `Application run failed`, logs in through the seeded
operator credential, stops the process, then repeats startup/login against the
same `./data/xa-mass-sqlite/xa_mass.db` file. Its `summary.json` records
`defaultProfile`, `defaultProfileLogObserved`, `sqlitePath`, `restartCount`,
health/login checks, `sameSqliteRestart`, `redisNamespaceMode`, and
`logFailureScan`. The profile-name field is a log-observed signal; the stronger
default-startup proof is no-arg jar startup, default SQLite path creation,
health, operator login, and same-file restart.

The smoke intentionally uses the default no-arg server port. If
`http://127.0.0.1:8088/actuator/health` is already serving before the smoke
starts, the runner writes a `blocked` / `port-precheck` summary. The proof
summary writer keeps that artifact visible as `artifact-metadata` and does not
count it as startup, restart, or operation-level proof.

Proof summary artifact:

```bash
node xa-mass-testing/scripts/write-proof-summary.mjs --job local
```

The summary writer consumes surefire XML, platform-confidence summaries,
default-startup summaries, and chaos/perf/soak JSON reports that already exist
under `target/`. It emits project proof class definitions and marks recognized
evidence with `proofClass`, `proofLines`, `proofQuestion`, `evidenceShape`,
`gateType`, `credentialRouteFamilies`, `authorizedPositiveChecks`, and
`claimScope`. It does not run proof itself and does not replace
`doc/PROOF_REGISTRY.md`. Perf/soak release
interpretation is defined in `proof/perf-soak-release-evidence.json`: hard
pass/fail signals remain runner invariants, while latency/throughput values are
trend-only until a calibrated baseline exists.

For clean CI evidence, workflows pass scoped input directories such as
`--test-report-dir`, `--platform-confidence-dir`, `--server-default-startup-dir`,
`--chaos-dir`, `--perf-dir`, and `--soak-dir`. An unscoped local summary is an
aggregate view and may include stale `target/` artifacts from earlier runs.

The polling scheduling soak is a manual or scheduled lane, not a PR gate. It drives SDK polling workers through the engine scheduling mainline and writes report JSON plus canonical trace JSONL under `target/soak-reports/` and `target/soak-traces/`. Its pass/fail proof is runtime/aggregate/result/trace-first: task terminal state, `TaskWorkRuntime` counters, active lease drain, SDK result windows, worker metrics, `JsonlExecutionEventSink` drop count, and `xa-mass-trace` validation/stats. The report includes `proof.runtimeInvariants`, a structured issue list that identifies whether failure came from task terminal count, runtime work counters, visible results, active lease drain, trace validation/drop, late-worker participation, trace analyzer failure, or worker failures. The `proof` bundle also groups `resultSequentialRead`, `workerMetrics`, `workerLifecycle`, `deliveryDiagnostics`, `trace`, and `failureSamples` so scheduled runs have one stable diagnostic entry point. Set `-Dmass.soak.failureEveryNth=N` to run mixed-result or all-failed profiles; the runner verifies expected terminal reasons and success/failed runtime counters from that profile and, when trace is enabled, binds a representative sample task into the named `mixed-result-terminal-convergence` or `all-failed-terminal-convergence` analyzer. Set `-Dmass.soak.processingJitterMillis=N -Dmass.soak.processingJitterSeed=S` to make processing jitter deterministic and report-visible in `config` and `proof.matrixProfile`. Set `-Dmass.soak.scenarioId=polling-soak-noisy-mixed-result` to select the current scenario-ledger noisy mixed-result row inside the runner; it defaults to deterministic jitter seed `20260602`, jitter bound `25ms`, and `failureEveryNth=5`, while explicit JVM properties still override those defaults. This row is seeded mixed-result soak proof, not dropped-result/retry proof. Set `-Dmass.soak.initialWorkerCount=N -Dmass.soak.lateWorkerStartAfterMillis=M -Dmass.soak.requireLateWorkerWork=true` to run a late-worker-join profile where only part of the polling fleet is online at the start; when trace is enabled, the runner records an actual late-worker task sample and runs the `late-worker-backfill` trace analyzer into `proof.trace.analyses`. Named soak analyzers receive the trace sink dropped count, so known dropped trace events cannot silently pass analyzer proof. See [`SOAK_TESTING_ROADMAP.md`](SOAK_TESTING_ROADMAP.md).

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

Polling Redis runtime owner restart/reconnect recovery chaos:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true -Dmass.sdk.chaos.forceExit=false -Dmass.sdk.chaos.taskMessageLeaseSeconds=2 -Dmass.sdk.chaos.timeoutSeconds=30 compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.chaos.SdkPollingRedisRestartRecoveryChaosRunner
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

List current worker-fault scenario ledger rows:

```bash
xa-mass-testing/scripts/list-worker-fault-scenarios.sh
```

Direct scenario-id entrypoint for a single existing probe:

```bash
xa-mass-testing/scripts/run-worker-fault-scenario.sh polling-lease-expiry-redispatch
```

`fault.dropped-result-retry` is a current scenario-ledger alias for the same
polling lease-expiry runner. It records the selected scenario id and
`faultShape=dropped-result-retry` in the report, but it does not introduce a
second runner or a separate PR gate row.

## Reading Rule

Start from the runner class, then read the matching report artifact.

Look at these first:

- perf: `wallClock.totalMillis`, release cost, runtime work counters
- SDK transport harness: top-level `transport` matrix axis, `actualTransport`,
  `tasks.terminalReasons`, `runtimeWork`, `deliveryQueue`, `workerMetrics`
- chaos: `task.runtime` counters, active lease count, task terminal reason, trace `byType`, then worker online/offline transitions
- chaos report field `task.reviewMessages` is residue/report context when
  present; do not treat it as the runner's primary correctness proof

Use repo-level docs only for system-level policy:

- [../doc/TESTING_INDEX.md](../doc/TESTING_INDEX.md)
- [../xa-mass-testing/VERIFIED_RUNBOOK.md](../xa-mass-testing/VERIFIED_RUNBOOK.md)
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
- Redis-backed runtime owner restart/reconnect during active leased work; the runner reuses `sched.retry-redispatch` and analyzer `lease-expiry-redispatch`. Redis process kill, partition/failover, lease-clock skew, and multi-node presence flap are not covered by this probe. They need a deterministic infra-fault harness, explicit runtime clock seam where relevant, and proof-registry ownership before this module can claim worker-fault matrix proof for them. Do not fake those failures through worker-pack local state, capability attributes, or test-only runtime hooks.

The PR-gated chaos probes are the three distributed-edge runtime recovery paths: polling lease-expiry redispatch, websocket lease-expiry redispatch, and websocket late-result replay. Their main assertions are runtime/aggregate/trace-first; compatibility projection is kept in reports only for bounded diagnosis. `fault.dropped-result-retry` is a report-visible alias over the polling lease-expiry proof, not a fourth PR bundle entry. Result-shape probes such as all-failed, mixed-results, and retry-exhausted remain scheduled/manual support because their primary proof lives in the engine/server/trace convergence chain. Websocket disconnect/reconnect also remains a useful manual probe until it is reduced to one crisp mechanism invariant instead of a mixed behavior bundle. All chaos probes still write report JSON under `target/chaos-reports/`.

`scripts/run-chaos-smokes.sh` resolves its PR scenario ids through `WorkerFaultScenarioCli` and enforces this source-level rule before running the probes: PR-gated chaos smoke runners must not import or call compatibility projection helpers or projection-derived stats such as `TaskMessageStats`, `TaskMessageAttemptStats`, `getTaskMessage*`, or direct detail-store access. Report/audit helpers may still live under `chaos.support`, but their primary correctness proof must stay runtime/trace-first.

These probes stay at the SDK embedded-runtime layer. Matching Boot-shell HTTP behavior should be verified separately under `xa-mass-server` E2E.
