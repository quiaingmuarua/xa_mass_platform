# Trace Agent Observability Roadmap

Status: proposed convergence roadmap.

This roadmap extends the existing `xa-mass-trace` operator module with
agent-consumable digest and operational health primitives. The target is that
an AI agent can diagnose task health, detect operational anomalies, and
understand system-wide pressure through structured CLI/tool commands without
needing to synthesize raw event streams or run multiple queries to form a
basic picture.

The existing trace infrastructure is already strong:

- 32 stable `ExecutionEventType` values with OTel-ready schema
- `JsonlExecutionEventSink` with async writing, rotation, and overflow policy
- `TraceEventLogger` with 1100+ lines of engine lifecycle emission
- `DuckDbTraceQueryBackend` with in-process DuckDB over canonical JSONL
- 6 CLI commands (timeline, query, stats, assignment, validate, analyze) with
  `--json` output
- 18 correctness scenario analyzers
- `TraceOperatorService` as a clean service facade

This roadmap does not replace or redesign any of the above. It adds a
compression and health assessment layer on top of it.

## Current Gap

All 18 existing scenario analyzers are correctness proofs: they verify that a
specific integrated flow produced the right event sequence with the right
fields. They answer "did this scenario work correctly?" They do not answer:

- **"Is this task healthy?"** — requires combining timeline, stats, progress
  snapshot, and anomaly detection into one compressed view
- **"What's wrong with this task?"** — requires pattern recognition for
  operational problems (dispatch stall, retry storm, lease churn, callback
  rejection spike)
- **"What's happening system-wide?"** — no current command operates across
  tasks; all queries are task-scoped
- **"What should I do next?"** — no current output suggests follow-up
  commands or drill-down paths

An agent calling `timeline --task-id X --json` gets up to 500 raw event rows.
That is too much unstructured data for efficient agent consumption. The agent
needs compressed findings, not raw evidence.

## Owner Review

1. **This is an extension, not a rewrite.**
   The trace write path, event schema, query backend, existing scenario
   analyzers, and CLI are stable and proven. New digest and health commands
   build on `TraceOperatorService` and `TraceQueryBackend`. No schema changes,
   no new Maven modules, no new trace vocabulary.

2. **Agent-native means compressed structured output, not dashboards.**
   The agent doesn't look at charts. It needs: one JSON object with health
   status, top issues, progress funnel, and suggested next commands. The
   existing `--json` flag proves the output pattern works; digest adds
   compression and assessment on top.

3. **Operational health analyzers are a different category from correctness
   analyzers.**
   Correctness analyzers verify invariants: "this event must appear before
   that event." Operational health analyzers detect patterns: "dispatch hasn't
   progressed in N seconds," "retry rate exceeds threshold," "lease
   expiry rate is abnormal." They share infrastructure
   (`TraceScenarioAnalyzer`, `TraceQueryBackend`) but have fundamentally
   different questions and tolerances. Keep them in a separate registry or
   clearly tagged.

4. **System-wide overview requires unbounded-scan discipline.**
   Cross-task queries over all trace JSONL files can be expensive. System
   digest must aggregate stats and detect patterns through bounded DuckDB
   queries (GROUP BY, windowed counts), not by loading every event into
   memory. DuckDB's columnar scan makes this feasible, but the query design
   must enforce limits.

5. **Start from the CLI, not from HTTP or MCP.**
   The CLI already works for agent tool loops (Bash → parse JSON). HTTP
   adapter or MCP server is a later optimization. The existing picocli
   structure makes adding subcommands low-friction. Premature HTTP/MCP
   coupling adds dependency and deployment surface before the digest
   semantics are stable.

6. **Suggested actions are the highest agent-ROI field.**
   An output like `"suggestedActions": ["timeline --task-id X --message-id Y",
   "analyze --scenario retry-storm --task-id X"]` is what turns passive
   observation into an agent work loop. This is cheap to produce (static
   templates based on detected issues) and extremely high value for agent
   workflows.

## Boundary Decision

All new commands and analyzers live in `xa-mass-trace`. Module ownership is
unchanged: write path stays in `platform_infra/mass-trace-sink`, read/query
path stays in `xa-mass-trace`.

```text
xa-mass-trace
  owns: digest commands, operational health analyzers, system overview,
        agent response envelope, suggested-action templates
  extends: TraceOperatorService, XaMassTraceCli
  reads: canonical JSONL through DuckDbTraceQueryBackend

platform_infra/mass-trace-sink
  unchanged: schema, event types, sink implementation

xa-mass-engine
  unchanged: TraceEventLogger emission
```

## Non-Goals

1. No new trace event types. Operational health analyzers read existing events.
2. No HTTP server, MCP server, or daemon process in the first slices. CLI
   with `--json` is the delivery channel until digest semantics stabilize.
3. No real-time streaming or tailing. Digest operates over rotated JSONL
   snapshots, not a live event stream.
4. No Prometheus, Grafana, or other human-oriented metrics infrastructure.
5. No changes to `ExecutionEvent` schema or `ExecutionEventType` enum.
6. No agent-facing runtime mutation. Digest is read-only observation.
7. No session or state management in the CLI. Each command is stateless,
   same as existing commands.
8. No new Maven module. All code lives in `xa-mass-trace`.
9. No trace-driven runtime decisions. Observation only.

## Hard Rules

1. Digest and health commands must use the same `TraceQueryBackend` and
   `TraceSource` paths as existing commands. No parallel query infrastructure.
2. Operational health analyzers must not require new `ExecutionEventType`
   values. They read existing event patterns.
3. System-wide queries must be bounded. Every DuckDB query must have explicit
   LIMIT and must use aggregation (GROUP BY, COUNT, windowed functions)
   rather than full-event materialization.
4. New CLI commands must support `--json` from day one. Human-readable
   output is secondary.
5. `TraceOperatorService` remains the single facade. Digest commands add
   methods to it, not a parallel service.
6. Suggested actions must reference existing CLI commands with concrete
   arguments. They must not reference hypothetical future commands or
   external tools.
7. No slice may change existing command output shapes or break existing
   analyzer behavior.
8. Every operational health analyzer must document its detection threshold
   and false-positive expectation. Thresholds must be configurable or
   clearly documented as defaults.

## Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Digest compression loses critical detail | agent misses real issues because digest summary was too coarse | digest must include issue list with severity, and `suggestedActions` for drill-down into raw timeline/query/assignment commands |
| Operational health thresholds are noisy | agent treats normal variance as anomalies, leading to false investigation loops | start with conservative thresholds and few analyzers (TAO-2); tune through soak/chaos trace artifacts before expanding |
| System-wide DuckDB queries are slow on large trace directories | digest command becomes too slow for agent tool loops | enforce bounded queries; add `--since` time filter to scope system queries; document expected latency per file-count range |
| Suggested actions become stale as CLI evolves | agent follows outdated drill-down suggestions that produce errors | suggested actions are generated from the same command registry; add integration tests that verify suggested commands parse correctly |
| Overlap with OBSERVABILITY_AND_TEST_PROOF_ROADMAP | duplicated concepts between correctness proof observability and agent observability | this roadmap is complementary: OBS focuses on proof bundles and invariant checkers, TAO focuses on agent-consumable digest and operational pattern detection; cross-reference but do not merge |

## Cross-Roadmap Touchpoints

- [`OBSERVABILITY_AND_TEST_PROOF_ROADMAP.md`](./OBSERVABILITY_AND_TEST_PROOF_ROADMAP.md):
  OBS defines proof layers, runtime invariant checkers, and soak proof
  bundles. TAO digest may reference invariant checker results when they
  exist, but TAO must not depend on OBS delivery. TAO operational health
  analyzers are complementary to OBS correctness analyzers.
- [`TRACE_CONTRACT.md`](./TRACE_CONTRACT.md):
  TAO reads canonical events defined by the trace contract. TAO must not
  require contract changes. If TAO discovers that existing event types are
  insufficient for a specific health pattern, the gap should be filed as a
  trace contract proposal, not worked around with heuristic parsing.
- [`GRACEFUL_SHUTDOWN_LIFECYCLE_ROADMAP.md`](./GRACEFUL_SHUTDOWN_LIFECYCLE_ROADMAP.md):
  GSL-7 may add shutdown phase trace events. When those events exist, TAO
  system overview can include shutdown observation. TAO does not block on GSL.

## TAO-0 Task Health Digest Command

Goal: one CLI command that returns a compressed health summary for a single
task, usable as the entry point for agent diagnosis.

Scope:

- Add `digest` subcommand to `XaMassTraceCli`.
- Add `digest(TraceDigestRequest)` method to `TraceOperatorService`.
- The digest combines existing query primitives:
  - event count by type (from `stats`)
  - latest task status transition (from `timeline`)
  - progress funnel snapshot if `TASK_PROGRESS_SNAPSHOT` events exist
  - terminal reason if the task is terminal
  - callback acceptance/rejection ratio
  - retry count and expiry count
  - assignment summary (worker matched/used/budget counts)
  - anomaly flags (computed in TAO-2, but the digest response shape includes
    them from the start as an empty list until TAO-2 populates them)
- Output shape (JSON):
  ```
  {
    "taskId": "...",
    "source": "...",
    "eventCount": 142,
    "currentStatus": "RUNNING",
    "terminalReason": null,
    "progressFunnel": {
      "total": 50, "init": 0, "assigned": 3, "running": 3,
      "success": 44, "failed": 0, "expired": 0
    },
    "callbackSummary": {
      "accepted": 44, "duplicate": 2, "late": 0,
      "rejectedNoLease": 0, "rejectedNoAttempt": 0, "rejectedInvalid": 0
    },
    "retrySummary": { "retryResets": 3, "leaseExpiries": 1 },
    "assignmentSummary": {
      "dispatchRequests": 12, "dispatchSkips": 2,
      "matchAccepted": 10, "matchRejected": 1
    },
    "anomalies": [],
    "suggestedActions": [
      "timeline --path ... --task-id ... --json",
      "assignment --path ... --task-id ... --json"
    ]
  }
  ```
- Implement as bounded DuckDB aggregation queries, not by loading all
  timeline rows into Java and counting.
- `suggestedActions` are generated contextually:
  - always include `timeline` and `assignment` for non-terminal tasks
  - include `analyze --scenario single-message-success` when task is
    terminal with one message
  - include retry/lease drill-down when retry or expiry counts are nonzero

CLI usage:

```bash
xa-mass-trace digest --path trace-events --task-id task-123 --json
```

Acceptance:

- `digest` returns a single JSON object with all summary fields populated
  from one `TraceOperatorService.digest()` call.
- No field requires the caller to make a second query to interpret the
  summary.
- `suggestedActions` contains at least one valid CLI command string.
- DuckDB query count is bounded (target: ≤4 queries per digest call).
- Existing commands and analyzers are unaffected.

## TAO-1 Agent Response Envelope

Goal: standardize all `--json` output across existing and new commands with a
stable envelope that agents can parse uniformly.

Scope:

- Define `TraceAgentEnvelope<T>`:
  ```java
  public record TraceAgentEnvelope<T>(
      boolean ok,
      String command,
      String summary,
      T data,
      List<String> suggestedActions,
      String error
  ) {}
  ```
- `ok`: whether the command succeeded.
- `command`: the command name that produced this output (e.g., `digest`,
  `timeline`, `analyze`).
- `summary`: one-sentence human/agent-readable summary of the result
  (e.g., "task-123: RUNNING, 44/50 success, 3 active, no anomalies").
- `data`: the existing response payload (unchanged for backward
  compatibility).
- `suggestedActions`: contextual follow-up CLI commands. May be empty.
- `error`: error message when `ok=false`, null otherwise.
- Introduce `--envelope` flag on all commands. When set, wrap the existing
  JSON output in the envelope. `digest` uses envelope by default.
- Existing `--json` output without `--envelope` remains unchanged for
  backward compatibility.

Acceptance:

- All 6 existing commands + `digest` support `--envelope`.
- Envelope `summary` for `analyze` includes scenario pass/fail and issue
  count.
- Envelope `suggestedActions` for `analyze` failure includes `timeline`
  drill-down.
- Parsing `data` from the envelope produces the same object as calling
  `--json` without `--envelope`.
- No existing `--json` output changes shape.

## TAO-2 Operational Health Analyzers

Goal: detect operational anomaly patterns from trace evidence without
requiring the agent to interpret raw events.

Scope:

- Introduce `TraceHealthAnalyzer` interface (or reuse `TraceScenarioAnalyzer`
  with a clear id-prefix convention like `health-*`).
- Initial operational health analyzers (each reads canonical JSONL through
  `TraceQueryBackend`):

  **`health-dispatch-stall`**: Task has been READY or RUNNING for longer than
  expected without dispatch progress.
  - Reads: `TASK_STATUS_TRANSITION`, `DISPATCH_REQUESTED`, `DISPATCH_SKIPPED`,
    `ASSIGNMENT_SUMMARY`.
  - Detection: time gap between READY transition and first successful dispatch
    exceeds threshold, or no dispatch requested events exist after READY.
  - Default threshold: configurable, suggested 60s for BATCH tasks.

  **`health-retry-pressure`**: Retry reset rate is abnormally high relative
  to total message count.
  - Reads: `TASK_WORK_RETRY_RESET`, `TASK_PROGRESS_SNAPSHOT`.
  - Detection: retry reset count > threshold percentage of total messages.
  - Default threshold: configurable, suggested 30%.

  **`health-lease-churn`**: Lease expiry rate indicates workers are not
  completing work within lease windows.
  - Reads: `LEASE_EXPIRED`, `CALLBACK_ACCEPTED`.
  - Detection: expiry count / (expiry + accepted) exceeds threshold.
  - Default threshold: configurable, suggested 20%.

  **`health-callback-rejection-spike`**: High ratio of rejected callbacks
  indicates stale workers or protocol mismatches.
  - Reads: `CALLBACK_REJECTED_*`, `CALLBACK_ACCEPTED`.
  - Detection: rejection count / total callback count exceeds threshold.
  - Default threshold: configurable, suggested 10%.

  **`health-slow-convergence`**: Task has been RUNNING beyond expected
  duration without reaching TERMINAL.
  - Reads: `TASK_STATUS_TRANSITION` (READY→RUNNING timestamp), latest event
    timestamp.
  - Detection: elapsed time exceeds threshold and task is not yet terminal.
  - Default threshold: configurable, suggested from `maxRuntimeSeconds` when
    available in attrs, otherwise a default ceiling.

- Each analyzer returns `TraceScenarioReport` (reuse existing report shape)
  with structured issues.
- Register health analyzers in `TraceScenarioRegistry` alongside existing
  correctness analyzers. The `health-` prefix distinguishes them.
- `digest` command (TAO-0) runs all `health-*` analyzers and includes
  results in the `anomalies` field.

CLI usage for individual health analysis:

```bash
xa-mass-trace analyze --path trace-events --scenario health-dispatch-stall --task-id task-123 --json
```

Acceptance:

- Each health analyzer is individually callable through `analyze --scenario`.
- Each analyzer documents its detection threshold, inputs, and expected
  false-positive rate.
- `digest` anomalies field is populated by health analyzer results.
- Health analyzers do not modify or depend on correctness analyzers.
- At least one health analyzer is tested against a soak/chaos trace artifact
  where the condition is known to exist.

## TAO-3 System Operational Overview

Goal: provide a cross-task operational summary so the agent can understand
system-wide health without knowing specific task IDs.

Scope:

- Add `overview` subcommand to `XaMassTraceCli`.
- Add `overview(TraceOverviewRequest)` method to `TraceOperatorService`.
- The overview aggregates across all tasks in the trace path:
  - total event count and time range
  - task count by current status (estimated from latest
    `TASK_STATUS_TRANSITION` per distinct `taskId`)
  - top-N event types by count
  - anomaly summary: count of tasks with detected health issues
    (runs all `health-*` analyzers on each active task, bounded by top-N
    most-active tasks)
  - retry/expiry global rates
  - top-N problematic tasks ranked by issue count or anomaly severity
- Output shape (JSON):
  ```
  {
    "source": "...",
    "timeRange": { "earliest": "...", "latest": "..." },
    "totalEvents": 12450,
    "taskStatusCounts": { "RUNNING": 3, "TERMINAL": 12, "READY": 1 },
    "topEventTypes": [ ... ],
    "globalRetrySummary": { "retryResets": 15, "leaseExpiries": 4 },
    "problematicTasks": [
      { "taskId": "...", "anomalyCount": 2, "topAnomaly": "health-retry-pressure" }
    ],
    "suggestedActions": [
      "digest --path ... --task-id <top-problematic-task> --json"
    ]
  }
  ```
- Implementation uses bounded DuckDB aggregation:
  - `GROUP BY taskId` with `LIMIT` for task enumeration
  - Per-task health analysis bounded to top-N most-active tasks
- Add `--since` time filter to scope the overview window.
- Add `--top` flag to control how many problematic tasks to surface
  (default: 5).

CLI usage:

```bash
xa-mass-trace overview --path trace-events --since 2026-06-01T00:00:00Z --top 5 --json
```

Acceptance:

- `overview` returns a single JSON object with system-wide summary.
- Query is bounded by `--since` and `--top`; unbounded scan is rejected.
- `problematicTasks` list includes task IDs with anomaly counts.
- `suggestedActions` references `digest` for the top problematic task.
- Total DuckDB queries per overview call is bounded (target: ≤3 aggregation
  queries + N health analyzer runs where N ≤ `--top`).

## TAO-4 Agent Tool Adapter

Goal: provide a higher-bandwidth integration path for agent tool loops when
CLI process spawning becomes a bottleneck.

Scope:

- This slice is deliberately deferred. Only start after TAO-0 through TAO-2
  are stable and the digest semantics are validated through real agent usage.
- Evaluate adapter options:
  - **MCP server**: native integration for Claude Code and other MCP-aware
    agents. Each digest/overview/analyze command becomes an MCP tool.
    Requires MCP SDK dependency.
  - **Lightweight HTTP**: single-process HTTP adapter wrapping
    `TraceOperatorService`. Similar to AgentForge analysis daemon pattern
    but without session management (DuckDB cold-start is cheap).
  - **Long-running CLI with stdin/stdout JSON-RPC**: avoids process spawn
    overhead without adding HTTP dependency. One process, multiple requests.
- Decision criteria:
  - if primary agent consumer is Claude Code → MCP
  - if multiple heterogeneous agents → HTTP
  - if latency is the only concern → JSON-RPC stdin/stdout
- Regardless of adapter choice, `TraceOperatorService` remains the single
  backend facade. Adapter is a thin protocol translation layer.
- Borrow engineering patterns from AgentForge where applicable:
  - structured JSON envelope (already addressed in TAO-1)
  - primitive catalog pattern: each operation has a stable name, input shape,
    and output shape documented in a catalog
  - error handling: structured error with code and message, not stack traces

Acceptance:

- Adapter wraps `TraceOperatorService` with zero business logic in the
  adapter layer.
- All commands available through CLI are available through the adapter.
- Adapter does not introduce session state, connection pooling, or background
  processes beyond what the chosen protocol requires.
- Integration test proves round-trip: request → adapter → operator service →
  DuckDB → response through the adapter protocol.

## TAO-5 Guards, Docs, And Proof

Goal: prevent regression and keep the agent observability surface documented.

Scope:

- Update `xa-mass-trace/README.md` with new commands (digest, overview) and
  health analyzer inventory.
- Update `doc/TRACE_CONTRACT.md` if any health analyzer requires attr fields
  not yet standardized (unlikely given TAO relies on existing events, but
  verify).
- Add health analyzer test fixtures:
  - trace JSONL fixtures with known dispatch stall, retry pressure, lease
    churn conditions
  - integration tests proving detection against fixtures
- Add digest integration test:
  - produce trace JSONL from a representative scenario
  - call `digest` and verify summary fields against known scenario state
- Add `overview` integration test with multi-task trace fixture.
- Update `doc/README.md` with this roadmap reference.
- If TAO-4 lands, add adapter integration tests.

Acceptance:

- README documents all new commands with usage examples.
- Each health analyzer has at least one positive-detection test fixture.
- Digest integration test verifies summary accuracy against a known
  scenario.
- Overview integration test verifies cross-task aggregation.
- Documentation distinguishes correctness analyzers from health analyzers.

## Suggested Implementation Order

1. TAO-0 task health digest command.
2. TAO-2 operational health analyzers (parallel or immediately after TAO-0).
3. TAO-1 agent response envelope.
4. TAO-3 system operational overview.
5. TAO-5 guards, docs, and proof.
6. TAO-4 agent tool adapter (only after digest semantics are stable).

TAO-0 and TAO-2 are the highest ROI. TAO-0 gives agents a single entry
point for task diagnosis. TAO-2 gives agents anomaly detection without
manual event interpretation. Together they replace the current pattern of
"call timeline, stats, assignment, then figure out what's wrong" with "call
digest, read anomalies and suggested actions."

TAO-1 is low effort and high value but depends on TAO-0 to prove the
envelope shape. TAO-3 depends on TAO-2 for the health analyzers it
aggregates. TAO-4 should wait until real agent usage validates the digest
and health primitives.

## Verification Candidates

Digest end-to-end:

```bash
./mvnw -pl xa-mass-trace -am -Dexec.classpathScope=compile -Dmaven.test.skip=true compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.trace.cli.XaMassTraceCli -Dexec.args="digest --path trace-events --task-id task-123 --json"
```

Health analyzer individual:

```bash
./mvnw -pl xa-mass-trace -am -Dexec.classpathScope=compile -Dmaven.test.skip=true compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.trace.cli.XaMassTraceCli -Dexec.args="analyze --path trace-events --scenario health-dispatch-stall --task-id task-123 --json"
```

System overview:

```bash
./mvnw -pl xa-mass-trace -am -Dexec.classpathScope=compile -Dmaven.test.skip=true compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.trace.cli.XaMassTraceCli -Dexec.args="overview --path trace-events --since 2026-06-01T00:00:00Z --json"
```

Module test gate:

```bash
./mvnw -pl xa-mass-trace -am test
```
