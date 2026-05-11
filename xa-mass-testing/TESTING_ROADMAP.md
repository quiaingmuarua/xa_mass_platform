# Testing Roadmap

Last updated: 2026-05-11

Status: living planning document for `xa-mass-testing` and adjacent test-estate
backlog.
Owner: testing module.

This file is planning-only. It does not define current CI gate truth, current
coverage truth, or the repo-wide testing map.

Read current-state truth from:

- [../doc/TESTING_INDEX.md](../doc/TESTING_INDEX.md)
- [../doc/TESTING_BASELINE.md](../doc/TESTING_BASELINE.md)
- [../README.md](../README.md)
- [README.md](./README.md) for module-local runner inventory

Use this roadmap for future gaps, sequencing, and runner backlog only.

---

## Planning Scope

This roadmap tracks future work for:

- perf and chaos runner breadth in `xa-mass-testing`
- places where SDK embedded-runtime probes should be added or deepened
- gaps that still need matching Boot-shell E2E or engine-kernel coverage in
  their owning modules
- observability and report-quality improvements for test diagnosis

This roadmap is not the authority for:

- which lanes currently gate PRs
- what the minimum verification is for a given change
- the current repo-wide asset map

Those questions are answered in
[../doc/TESTING_INDEX.md](../doc/TESTING_INDEX.md).

## Trace Architecture

Understanding trace infrastructure is prerequisite to integrating it into the
test strategy.

### Trace layers

```text
Engine hot path
  -> TraceEventLogger            -> canonical emitter (xa-mass-engine)
       -> ExecutionEventSink     -> interface (mass-trace-sink)
            -> NoopExecutionEventSink       -> default (SDK without configuration)
            -> JsonlExecutionEventSink      -> async JSONL file writer (production)
            -> CapturingExecutionEventSink  -> in-memory (tests / chaos probes)
```

`TraceEventLogger` wraps `ExecutionEventSink` and translates business-level
calls such as `taskTerminalClosed` and `taskMsgStatusTransition` into
structured `ExecutionEvent` objects with:

- `eventType`: `ExecutionEventType` enum
- `identity`: `taskId`, `messageId`, `attemptId`, `workerId`,
  `workerContextId`, `leaseToken`
- `transition`: `src`, `dst`, `reason`
- `outcome`: `success`, `errorCode`, `detail`
- `attrs`: free-form key/value data such as `terminalReason`, `trigger`, or
  `source`

### Key `terminalReason` placement

`TASK_TERMINAL_CLOSED` events store `terminalReason` in
`attrs["terminalReason"]`, not in `transition.reason`.
`TraceEventAssertions.requireTerminalReason` should continue to enforce that
shape.

`TASK_WORK_STATUS_TRANSITION` events store the destination status in
`transition.dst` as the enum name, for example `"FAILED"` or `"SUCCESS"`.

### Existing trace test gap

`TraceEventLogCapture` in `xa-mass-engine` captures logback MDC strings, not
canonical `ExecutionEvent` objects. It is useful as historical signal, but it
does not machine-verify the trace contract.

### New trace test infrastructure

| Class | Location | Purpose |
| --- | --- | --- |
| `CapturingExecutionEventSink` | `xa-mass-testing/chaos/support` | In-memory `ExecutionEventSink` that accumulates events thread-safely |
| `TraceEventAssertions` | `xa-mass-testing/chaos/support` | Fluent assertions over `CapturingExecutionEventSink` for chaos and runner verification |

Wire into any chaos harness like this:

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

`TraceEventAssertions.summaryMap(taskId)` should continue to feed the `"trace"`
section of chaos report JSON.

---

## Phase 1 - CI Structural Fixes

Goal: make the test estate machine-verifiable. Probes that exist but are never
run in CI are documentation, not protection.

| Item | Status | What was done / next step |
| --- | --- | --- |
| Add `xa-mass-testing` compilation visibility | delivered | keep this module visible to CI so runner breakage is caught before manual use |
| Add chaos smoke gate | delivered | keep a fast PR-facing chaos smoke lane for high-signal recovery scenarios |
| Expand focused lifecycle gate | delivered | keep high-value mixed-result, callback-replay, and multi-round paths in the fast gate |
| Trace infrastructure | delivered | keep canonical `ExecutionEvent` assertions in the chaos layer |
| Perf regression threshold | pending | add threshold checks so perf smoke becomes machine-actionable instead of artifact-only |

Exit criterion: `xa-mass-testing` remains visible to CI, chaos smoke stays
PR-gated, and perf smoke gains threshold-based failure rules.

---

## Phase 2 - Terminal Path Completeness

Goal: every important `TaskTerminalReason` has deterministic end-to-end proof
beyond engine-only local tests.

| Item | Status | Lane | Gap being closed |
| --- | --- | --- | --- |
| Per-message retry exhaustion chaos probe | delivered | chaos (`xa-mass-testing`) | preserves retry-reset and terminal-reason proof under polling failure churn |
| `RETRY_BUDGET_EXHAUSTED` policy implementation | blocked | engine | requires a real terminal policy before an end-to-end test is meaningful |
| `ALL_MESSAGES_FAILED` Boot-shell E2E | pending | Boot-shell E2E (`xa-mass-server`) | server-side host path for all-fail convergence |
| Resume short-circuit Boot-shell E2E | pending | Boot-shell E2E (`xa-mass-server`) | verify resume on an already-finished paused task returns terminal directly |

Exit criterion: `ALL_MESSAGES_FAILED`, `MIXED_MESSAGE_RESULTS`,
`ALL_MESSAGES_SUCCEEDED`, `MANUAL_CANCELLED`, and `MAX_RUNTIME_REACHED` each
have at least one deterministic full-lifecycle test in the appropriate owning
lane. `RETRY_BUDGET_EXHAUSTED` remains explicitly deferred until the policy
exists.

---

## Phase 3 - Lifecycle Safety Under Concurrency

Goal: lifecycle commands issued while work is in flight do not leave the kernel
or host shell in an inconsistent state.

| Item | Lane | Scope |
| --- | --- | --- |
| Worker disconnect deterministic surrogate | engine test (`xa-mass-engine`) | inject worker-offline while a lease is active; verify expiry, release, and retry-reset behavior deterministically |
| Cancel-from-RUNNING concurrent dispatch chaos | chaos (`xa-mass-testing`) | verify cancel during active dispatch does not strand worker contexts or leases |
| Pause-then-resume under in-flight dispatch chaos | chaos (`xa-mass-testing`) | verify pause/resume transitions and redispatch behavior under active work |

Exit criterion: one deterministic engine surrogate exists for disconnect-driven
lease behavior, and the matching chaos runners cover real runtime recovery.

---

## Phase 4 - Transport And Storage Breadth

Goal: transport adapters and persistent storage paths have broader parity with
the default memory and current adapter paths.

| Item | Lane | Scope |
| --- | --- | --- |
| Socket chaos: disconnect | chaos (`xa-mass-testing`) | mirror the websocket disconnect proof on the socket adapter |
| Socket chaos: lease-expiry redispatch | chaos (`xa-mass-testing`) | mirror websocket lease-expiry takeover on the socket adapter |
| JDBC H2 failure plus lifecycle coverage | Boot-shell E2E (`xa-mass-server`) | extend host-shell coverage beyond a thin success-only persistent path |
| Cross-language failure plus retry | Boot-shell E2E (`xa-mass-server`) | extend Java and Node black-box coverage beyond success-only flows |

Exit criterion: all major transport adapters have at least one disconnect or
lease-expiry proof, and persistent storage paths cover more than a happy-path
assignment flow.

---

## Phase 5 - Observability And Contract Verification

Goal: test signal is machine-readable and aligned with the canonical trace
contract.

| Item | Scope |
| --- | --- |
| Trace contract baseline | extend `TraceEventAssertions` with explicit schema and identity checks |
| Chaos report CI adapter | fail CI when chaos report assertions or minimum trace signal are missing |
| Structured perf baseline file | keep perf-smoke results comparable against a checked-in baseline |
| DuckDB diagnosis workflow | document post-failure queries for JSONL trace sinks |

---

## Trace Integration Pattern For New Runners

Every new chaos runner should:

1. Instantiate `CapturingExecutionEventSink` at the top of `run()`.
2. Pass it into the matching `ChaosRuntimeHarness.create*` entry.
3. Add `TraceEventAssertions` after state assertions for terminal closure and
   scenario-specific event types.
4. Write `TraceEventAssertions.summaryMap(taskId)` into the report JSON.

This keeps every chaos report self-describing and reduces reruns during
post-mortem work.

---

## Sequencing

```text
Phase 1 (CI structural fixes)   -> prerequisite for reliable signal
Phase 2 (terminal completeness) -> can proceed in parallel once owner lanes are clear
Phase 3 (concurrency safety)    -> depends on stable chaos/engine signal
Phase 4 (transport + storage)   -> parallel once adapter-specific harness scope is clear
Phase 5 (observability)         -> continuous, folded into every new runner
```

Phase 1 stays highest priority because unrun probes do not protect mainline.

---

## Non-Goals

- Redis runtime testing before `mass-runtime-redis` implements the required
  queue and lease operations
- frontend or GUI testing beyond existing lint, type, unit, and build lanes
- bigger-scale load expansion before the current perf smoke baselines become
  insufficient
- retrofitting every historical engine-local trace helper in this module; new
  chaos work should use `CapturingExecutionEventSink` directly
