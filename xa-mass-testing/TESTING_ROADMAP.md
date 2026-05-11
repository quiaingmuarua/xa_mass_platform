# Testing Roadmap

Last updated: 2026-05-08

Status: living planning document for `xa-mass-testing` and the broader test estate.
Owner: testing module. Cross-lane references: [doc/TESTING_BASELINE.md](../doc/TESTING_BASELINE.md).

---

## Current State (verified against CI)

### CI workflows

| Workflow | Trigger | Jobs | Scope |
|---|---|---|---|
| `maven.yml` | every PR + push to main | `reactor-core`, `server-e2e`, `lifecycle-integration` | core gate |
| `external-worker-samples.yml` | every PR + push + daily | `cross-language-blackbox` | Java + Node cross-language |
| `redis-runtime.yml` | path-filtered PR + push + daily | `redis-runtime` | Redis runtime path |
| `perf-smokes.yml` | daily + manual | `perf-smokes` | perf regression |
| `frontend.yml` | path-filtered | `frontend` | lint / typecheck / test / build |

**`reactor-core` job:** `./mvnw -pl xa-mass-worker-pack -am test` — covers `xa-mass-engine`, `xa-mass-sdk`, `xa-mass-base`, all `platform_infra` modules. **`xa-mass-testing` is not a dependency of `xa-mass-worker-pack` and is never compiled or run in any CI job.**

**`server-e2e` job:** `./mvnw -pl xa-mass-server test` — runs all 59 server tests (lifecycle × 8, results × 4, assignment × 23, console, audit, API unit). No `@Disabled`. Timeout 20 min.

**`lifecycle-integration` job:** focused fast gate — 3 classes: `TaskApiIntegrationTest`, `TaskApiFailureResultIntegrationTest`, `TaskApiLifecycleGuardsIntegrationTest`. Timeout 10 min.

### What is actually covered

| Concern | Coverage | Where |
|---|---|---|
| Happy path (all succeed) | solid | `TaskApiIntegrationTest`, socket + SDK variants |
| Failure result + RETRY_EXHAUSTED message | solid | `TaskApiFailureResultIntegrationTest` |
| Mixed results + MIXED_MESSAGE_RESULTS | solid | `TaskApiMixedResultsIntegrationTest` |
| Cancel from READY/PAUSED → TERMINAL | solid | `TaskApiLifecycleGuardsIntegrationTest.terminateWorksForReadyAndPausedTasks` |
| Cancel from RUNNING → TERMINAL | solid | `TaskApiTerminateRunningIntegrationTest` |
| Pause / resume cycle | solid | `TaskApiLifecycleGuardsIntegrationTest`, `TaskApiPauseCompletionIntegrationTest`, `TaskApiResumeAndCompleteIntegrationTest` |
| Lifecycle guard rules (invalid transitions, field validation) | solid | `TaskApiLifecycleGuardsIntegrationTest` (11 methods) |
| Callback replay idempotency | solid | `TaskApiCallbackReplayIntegrationTest` |
| batchSize > 1, multi-round dispatch | solid | `TaskApiMultiRoundDispatchIntegrationTest`, `TaskApiMultiTaskAssignmentIntegrationTest` |
| Worker routing + context attributes | solid | `TaskApiWorkerContextAttributeRoutingIntegrationTest`, `TaskApiTargetedWorkerDebugIntegrationTest` |
| Workload class (INTERACTIVE vs BULK) | solid | `TaskApiWorkloadClassIntegrationTest` |
| Cross-language Java + Node (polling/ws/socket) | solid | 6 black-box tests + `run-external-worker-samples.sh` |
| JDBC H2 storage path | thin | `H2ExternalWorkerPollingApiIntegrationTest` (one assignment scenario only) |
| JDBC Postgres storage path | thin | `PostgresExternalWorkerPollingApiIntegrationTest` (CI only on path filter) |
| Redis late-replay protection | one test | `RedisRuntimeLateReplayE2eScenario` |
| Engine concurrency (races, refill, release) | solid | `TaskConcurrencyAcceptanceTest` (engine module) |
| `RETRY_BUDGET_EXHAUSTED` terminal reason | engine unit only | `TaskManagerLifecycleTest` (open-intake policy path) — no Boot-shell E2E, no chaos |
| Perf: hot-path regression | daily smoke | `run-perf-smokes.sh` (workload mix + interactive retry wakeup) |
| Chaos: WebSocket/Polling recovery | manual/scheduled only | 6 runners in `xa-mass-testing` — **zero CI gate** |
| `xa-mass-testing` compilation | **none** | module is never compiled in any CI job |
| Trace event contract | **none** | `TraceEventLogCapture` captures MDC strings, not `ExecutionEvent` objects |

### Confirmed gaps

1. **`xa-mass-testing` not in CI** — new chaos runners (including the trace-wired ones) are not compiled or run in any workflow. A broken runner is invisible until someone manually executes it.
2. **Chaos runners have no CI gate** — all 6 runners are manual/scheduled only. No PR is blocked by a failing chaos scenario.
3. **`RETRY_BUDGET_EXHAUSTED` coverage is engine-unit only** — no SDK-embedded or Boot-shell path exercises this terminal reason end-to-end.
4. **Resume short-circuit not verified** — existing resume tests go `PAUSED → READY → RUNNING → TERMINAL`. The case where `resumeTask` is called on a task that finished underneath (while paused) is not exercised.
5. **`lifecycle-integration` fast gate is very narrow** — 3 test classes. Several high-value Boot-shell paths (mixed results, callback replay, multi-round dispatch) are only verified in the slower `server-e2e` job.
6. **JDBC H2 and Postgres are thin** — one success-path scenario each. No failure, retry, or lifecycle-command coverage on persistent storage.
7. **Socket adapter chaos** — no disconnect or lease-expiry chaos probe for the socket transport (WebSocket and Polling both have probes).
8. **Perf smoke has no regression gate** — report is uploaded as artifact but not checked against thresholds; a 10× regression would not fail CI.
9. **Trace contract is not machine-verified** — `TraceEventLogCapture` captures MDC log strings in existing engine tests; no test asserts on the actual `ExecutionEvent` object model. The canonical trace schema (`xa.mass.execution-event.v1`) is described in `doc/TRACE_CONTRACT.md` but never automatically validated.

---

## Trace Architecture

Understanding trace infrastructure is prerequisite to integrating it into the test strategy.

### Trace layers

```
Engine hot path
  └── TraceEventLogger           ← canonical emitter (xa-mass-engine)
        └── ExecutionEventSink   ← interface (mass-trace-sink)
              ├── NoopExecutionEventSink    ← default (SDK without configuration)
              ├── JsonlExecutionEventSink   ← async JSONL file writer (production)
              └── CapturingExecutionEventSink ← in-memory (tests / chaos probes)
```

`TraceEventLogger` wraps `ExecutionEventSink` and translates business-level calls (e.g., `taskTerminalClosed`, `taskMsgStatusTransition`) into structured `ExecutionEvent` objects with:
- **eventType** — `ExecutionEventType` enum (30 values across TASK / MSG / WORKER / DISPATCH / CALLBACK / LEASE categories)
- **identity** — `taskId`, `messageId`, `attemptId`, `workerId`, `workerContextId`, `leaseToken`
- **transition** — `src` / `dst` / `reason` (state machine transitions)
- **outcome** — `success` / `errorCode` / `detail`
- **attrs** — free-form key/value for supplementary data (e.g., `terminalReason`, `trigger`, `source`)

### Key `terminalReason` placement

`TASK_TERMINAL_CLOSED` events store `terminalReason` in `attrs["terminalReason"]`, **not** in `transition.reason`. `TraceEventAssertions.requireTerminalReason` handles this correctly.

`TASK_WORK_STATUS_TRANSITION` events store the destination status in `transition.dst` as the enum name (e.g., `"FAILED"`, `"SUCCESS"`).

### Existing trace test gap

`TraceEventLogCapture` (in `xa-mass-engine` tests) captures logback MDC log strings, not `ExecutionEvent` objects. It verifies that `TraceEventLogger` logged something but cannot assert on the canonical event schema fields. This means trace contract violations (wrong field, wrong type, missing identity) would pass existing tests.

### New trace test infrastructure (delivered in this cycle)

| Class | Location | Purpose |
|---|---|---|
| `CapturingExecutionEventSink` | `xa-mass-testing/chaos/support` | In-memory `ExecutionEventSink`; accumulates events via `CopyOnWriteArrayList`; thread-safe |
| `TraceEventAssertions` | `xa-mass-testing/chaos/support` | Fluent assertions over `CapturingExecutionEventSink`; calls `ChaosSupport.require` on failure |

Wire into any chaos harness:

```java
CapturingExecutionEventSink traceSink = new CapturingExecutionEventSink();
ChaosRuntimeHarness runtime = ChaosRuntimeHarness.createPolling(config, traceSink);
// ... run scenario ...
TraceEventAssertions.of(traceSink)
    .forTask(task.getTid())
    .requireEventType(ExecutionEventType.TASK_TERMINAL_CLOSED)
    .requireTerminalReason("ALL_MESSAGES_FAILED")
    .requireMessageStatusTransitions("FAILED", 3);
```

`TraceEventAssertions.summaryMap(taskId)` produces a report section (`byType` counts) that is written into every chaos report JSON under `"trace"`.

---

## Phase 1 — CI Structural Fixes ✅ DELIVERED

**Goal:** make the test estate machine-verifiable. Probes that exist but are never run by CI are not safety net — they're documentation.

| Item | Status | What was done |
|---|---|---|
| Add `xa-mass-testing` compilation to `reactor-core` job | ✅ Done | Added `./mvnw -q -pl xa-mass-testing -DskipTests compile` step after core reactor tests in `reactor-core` job |
| Add chaos smoke gate to `maven.yml` | ✅ Done | New `chaos-smokes` job running `run-chaos-smokes.sh`; 3 probes gate every PR; chaos reports uploaded as artifacts |
| Expand `lifecycle-integration` fast gate | ✅ Done | Added `TaskApiMixedResultsIntegrationTest`, `TaskApiCallbackReplayIntegrationTest`, `TaskApiMultiRoundDispatchIntegrationTest` — fast gate now covers 6 test classes |
| Trace infrastructure | ✅ Done | `CapturingExecutionEventSink` + `TraceEventAssertions` delivered; all 3 CI chaos probes emit and assert on canonical `ExecutionEvent` objects |
| Perf regression threshold | ⏳ Pending | Add threshold check to `run-perf-smokes.sh` that exits non-zero when `wallClock.totalMillis` exceeds a configurable baseline |

**Exit criterion:** every PR compiles `xa-mass-testing` ✅. Three chaos probes run and gate on PR ✅. All probes emit trace events and `TraceEventAssertions` validates the canonical schema ✅. Perf smoke regression threshold: pending.

---

## Phase 2 — Terminal Path Completeness (partially delivered)

**Goal:** every `TaskTerminalReason` has end-to-end coverage beyond engine unit tests.

| Item | Status | Lane | Gap being closed |
|---|---|---|---|
| Per-message retry exhaustion chaos probe | ✅ Done | chaos (`xa-mass-testing`) | `SdkPollingMessageRetryExhaustedChaosRunner`: `maxRetryCount=2`, worker always fails, each message burns 3 attempts → `RETRY_EXHAUSTED` message finalization → `ALL_MESSAGES_FAILED`; `TASK_WORK_RETRY_RESET` asserted in trace; wired to `chaos-smokes` CI job |
| `RETRY_BUDGET_EXHAUSTED` policy implementation | ⛔ Blocked | engine | **No triggering policy exists.** `AllWorkFinalTaskTerminalPolicy` does not emit this reason. Requires a new `RetryBudgetTaskTerminalPolicy`. Tracked in `doc/CURRENT_GAPS.md`. Do not write an E2E test for this until the policy is implemented. |
| `ALL_MESSAGES_FAILED` Boot-shell E2E | ⏳ Pending | Boot-shell E2E (`xa-mass-server`) | Server-side HTTP path for all-fail result convergence (chaos layer exists + CI gated; HTTP layer missing) |
| Resume short-circuit Boot-shell E2E | ⏳ Pending | Boot-shell E2E (`xa-mass-server`) | Extend `TaskApiResumeAndCompleteIntegrationTest`: add case where all messages succeed while task is `PAUSED`; verify `resumeTask` returns `TERMINAL` directly |

**Exit criterion:** `ALL_MESSAGES_FAILED` (direct + retry-exhausted path), `MIXED_MESSAGE_RESULTS`, `ALL_MESSAGES_SUCCEEDED`, `MANUAL_CANCELLED`, `MAX_RUNTIME_REACHED` all have at least one deterministic test that goes through the full lifecycle. `RETRY_BUDGET_EXHAUSTED` is explicitly deferred to policy implementation. Each chaos probe includes trace assertions on `TASK_TERMINAL_CLOSED.attrs["terminalReason"]`.

---

## Phase 3 — Lifecycle Safety Under Concurrency

**Goal:** lifecycle commands issued while messages are in-flight do not leave the engine in an inconsistent state.

| Item | Lane | Scope |
|---|---|---|
| Worker disconnect deterministic surrogate | engine test (`xa-mass-engine`) | inject worker-offline event while a lease is active in process; verify attempt closes `EXPIRED`, context releases to `IDLE`, message resets if retryable — prerequisite for all transport disconnect chaos work |
| Cancel-from-RUNNING concurrent dispatch chaos | chaos (`xa-mass-testing`) | new `SdkPollingCancelWhileRunningChaosRunner`: task `RUNNING`, cancel mid-dispatch; verify no stuck `OCCUPIED` context; use `CapturingExecutionEventSink` to assert `WORKER_CONTEXT_STATUS_TRANSITION` → `IDLE` after cancel |
| Pause-then-resume under in-flight dispatch chaos | chaos (`xa-mass-testing`) | new `SdkPollingPauseResumeChaosRunner`: pause after first dispatch batch; resume triggers redispatch; verify attempt count and final convergence; assert `TASK_STATUS_TRANSITION` `RUNNING → PAUSED` and `PAUSED → READY` in trace |

**Exit criterion:** the worker-disconnect surrogate is in the `xa-mass-engine` test suite. Cancel-while-running and pause/resume-while-running each have a chaos probe in `xa-mass-testing` and are included in the Phase 1 `chaos-smokes` CI job. All new probes include trace assertions.

---

## Phase 4 — Transport & Storage Breadth

**Goal:** socket adapter and JDBC storage path have parity with WebSocket/Polling/memory paths.

| Item | Lane | Scope |
|---|---|---|
| Socket chaos: disconnect | chaos (`xa-mass-testing`) | new `SdkSocketDisconnectChaosRunner` — mirror of WS disconnect probe; use `CapturingExecutionEventSink` to assert `LEASE_EXPIRED` and `TASK_WORK_RETRY_RESET` events |
| Socket chaos: lease-expiry redispatch | chaos (`xa-mass-testing`) | new `SdkSocketLeaseExpiryRedispatchChaosRunner` — mirror of WS lease-expiry probe |
| JDBC H2 failure + lifecycle coverage | Boot-shell E2E (`xa-mass-server`) | extend `H2ExternalWorkerPollingApiIntegrationTest`: add failure result and cancel-from-RUNNING scenarios on H2 path |
| Cross-language failure + retry | Boot-shell E2E (`xa-mass-server`) | extend Java and Node black-box tests to submit failure and verify retry + final convergence; current black-box tests only cover success path |

**Exit criterion:** all three transport adapters have at least one chaos disconnect probe. JDBC H2 path has failure + lifecycle coverage. Cross-language tests cover at least one failure+retry scenario per language.

---

## Phase 5 — Observability & Contract Verification

**Goal:** test signal is machine-readable; trace contract is automatically verified against `doc/TRACE_CONTRACT.md`.

| Item | Scope |
|---|---|
| Trace contract baseline | Extend `TraceEventAssertions` with a `requireContractCompliance(ExecutionEvent)` method that checks: schema field is `xa.mass.execution-event.v1`, eventType is non-null, identity.taskId is non-null for TASK_* events, transition fields are present for status-transition events; wire into all chaos probes |
| Chaos report CI adapter | Reads chaos report JSON from `target/chaos-reports/`, exits non-zero if assertion failed or `trace.totalForTask < 5` or report missing; wire into the Phase 1 `chaos-smokes` CI job |
| Structured perf baseline file | Store baseline metrics in `xa-mass-testing/perf-baseline.json`; `run-perf-smokes.sh` diffs the run against the baseline and fails on regression |
| DuckDB diagnosis workflow | Document in `xa-mass-testing/README.md`: after a chaos failure, use DuckDB against the JSONL sink (if `JsonlExecutionEventSink` was active) to query event sequences; standard queries: `SELECT eventType, transition_src, transition_dst, ts FROM events WHERE taskId = ?` — see `platform_infra/mass-trace-sink/README.md` for query examples |

---

## Trace Integration Pattern for New Runners

Every new chaos runner **must**:

1. Instantiate `CapturingExecutionEventSink traceSink = new CapturingExecutionEventSink()` at the top of `run()`
2. Pass it to `ChaosRuntimeHarness.createPolling(config, traceSink)` or `createWebSocket(config, traceSink)`
3. After all `ChaosSupport.require` state assertions, add `TraceEventAssertions.of(traceSink)` assertions for:
   - `requireEventType(TASK_STATUS_TRANSITION)` — verifies state machine events were emitted
   - `requireEventType(TASK_TERMINAL_CLOSED)` — verifies terminal convergence was traced
   - `requireTerminalReason(...)` — verifies the correct terminal reason was captured
   - Any scenario-specific event types (e.g., `LEASE_EXPIRED` for lease-expiry probes, `TASK_WORK_RETRY_RESET` for retry probes)
4. Include `"trace", TraceEventAssertions.of(traceSink).summaryMap(task.getTid())` in the `ChaosReportWriter.write` call

This makes every chaos report self-describing: the `trace.byType` section shows exactly which event types were emitted, making post-mortem analysis straightforward without needing to re-run the scenario.

---

## Sequencing

```
Phase 1 (CI structural fixes)   ─── prerequisite for everything else
Phase 2 (terminal completeness) ─── start immediately; no Phase 1 dependency
Phase 3 (concurrency safety)    ─── depends on Phase 1 chaos gate being in place
Phase 4 (transport + storage)   ─── parallel with Phase 3 once socket harness is scoped
Phase 5 (observability)         ─── continuous; each new runner should add trace assertions inline
```

Phase 1 is the highest-priority item because probes that compile locally but are never run in CI do not protect the main branch. The trace infrastructure (`CapturingExecutionEventSink`, `TraceEventAssertions`) is already delivered and wired into the two Phase 1 chaos probes.

---

## Non-Goals

- Redis runtime testing: not a priority until `mass-runtime-redis` implements queue/lease operations.
- GUI/frontend testing beyond current lint/type/unit: out of scope for this module.
- Load scaling beyond current perf defaults: not actionable until the engine shows measurable regression on the existing baselines.
- Replacing `TraceEventLogCapture` in existing engine unit tests: that is an engine module concern. New chaos probes in `xa-mass-testing` use `CapturingExecutionEventSink` directly.
