# XA Mass Platform Agent Handoff

This is the fastest entry point for coding agents. Keep it short. Use the owner docs under `doc/`, `transport/`, and the owning module roots for details.

## 0. TL;DR

- XA Mass Platform is a general distributed task scheduling platform.
- Stable kernel: `Task / TaskMsg / TaskMsgAttempt / assignment / result / audit / terminal policy`.
- Transport is three explicit channels: task dispatch, result ingest, and system events.
- WebSocket is one transport adapter, not the product boundary.
- Polling/pull workers are mainline, not side features.
- Runtime entry is SDK-first; HTTP pages and demo APIs are validation shells.
- Observability is logs, trace, and bounded diagnostics; do not add scan-heavy hot-path state.
- Prefer idempotent operations and bounded queues over richer but expensive control-plane projections.
- Official Java baseline is Java 21 with virtual threads used through explicit runtime abstractions.
- Real Boot entry is `xa-mass-dev-app`; embedded runtime composition lives in `xa-mass-sdk`.
- Core acceptance is `perf + concurrency + Boot-shell E2E`.

## 1. Required Reading

For a new session, read these before changing behavior:

1. [README.md](README.md)
2. [doc/AGENT_BASELINE.md](doc/AGENT_BASELINE.md)
3. [doc/STATE_MACHINE_BASELINE.md](doc/STATE_MACHINE_BASELINE.md)

Everything else is on-demand through the task-type map below.

Core acceptance fast path:

- treat `perf`, `concurrency`, and Boot-shell `E2E` as the three core acceptance layers
- current runnable `perf` surface lives in `xa-mass-testing`
- current runnable `concurrency` surface lives in `xa-mass-engine`
- current runnable `chaos` probes live in `xa-mass-testing`
- current runnable `E2E` surface lives in `xa-mass-dev-app`
- `chaos` belongs in scheduled or release-style verification until the suite is broad and stable enough for stricter gating
- `xa-mass-testing` holds cross-cutting acceptance tooling and SDK embedded-runtime probes
- SDK-driven embedded-runtime harnesses in `xa-mass-testing` are the fast transport-aware system probe lane before full Boot-shell E2E
- `concurrency` is a required acceptance lane for race-sensitive lifecycle work
- all other tests are support coverage for bug localization, invariants, and fast regression checks

Canonical trust order:

1. code
2. verified runtime behavior
3. this handoff
4. active owner docs and ledgers (`doc/*`, `transport/*`, `DEPRECATION_LEDGER.md`)
5. module README files
6. refactor inventories and older notes only after re-verification

## 2. Task-Type Reading Map

Start here based on the change:

- transport module structure, adapter/runtime boundaries, or adapter onboarding: [transport/AGENTS.md](transport/AGENTS.md)
- websocket adapter/transport: [transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md](transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md)
- high-volume model compression or queue-first runtime shape: [doc/HIGH_VOLUME_MODEL_BASELINE.md](doc/HIGH_VOLUME_MODEL_BASELINE.md)
- high-volume transport worker-event delivery: [transport/TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md](transport/TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md)
- lifecycle/state transitions: [doc/STATE_MACHINE_BASELINE.md](doc/STATE_MACHINE_BASELINE.md), [doc/TRACE_CONTRACT.md](doc/TRACE_CONTRACT.md), [doc/E2E_BASELINE.md](doc/E2E_BASELINE.md)
- HTTP/API contracts: [doc/INTERNAL_API_REFERENCE.md](doc/INTERNAL_API_REFERENCE.md)
- startup/runtime verification: [doc/VERIFIED_RUNBOOK.md](doc/VERIFIED_RUNBOOK.md)
- perf/concurrency/core acceptance: [doc/TESTING_BASELINE.md](doc/TESTING_BASELINE.md), [doc/VERIFIED_RUNBOOK.md](doc/VERIFIED_RUNBOOK.md), [xa-mass-engine/README.md](xa-mass-engine/README.md), [xa-mass-testing/README.md](xa-mass-testing/README.md)
- integration/E2E coverage: [doc/E2E_BASELINE.md](doc/E2E_BASELINE.md), [xa-mass-dev-app/README.md](xa-mass-dev-app/README.md)
- policy ownership or interactions: [doc/engine/POLICY_INTERACTION_BASELINE.md](doc/engine/POLICY_INTERACTION_BASELINE.md)
- dispatch/result flow: [doc/engine/TASK_EXECUTION_FLOW.md](doc/engine/TASK_EXECUTION_FLOW.md)
- legacy/compatibility/deprecation work: [DEPRECATION_LEDGER.md](DEPRECATION_LEDGER.md), [transport/refactor/WEBSOCKET_ADAPTER_CURRENT_INVENTORY.md](transport/refactor/WEBSOCKET_ADAPTER_CURRENT_INVENTORY.md)

## 3. Agent Behavior Contract

These rules are hard constraints for coding agents. Violating them is a regression even if code compiles and tests pass.

Compatibility and convergence:

- Within this repository, there is no compatibility obligation for superseded internal paths. Update in-repo callers instead of preserving the old path.
- Agents must converge superseded internal paths by: (1) marking them deprecated, (2) migrating direct in-repo callers to the identified mainline/source of truth, and (3) removing the old path.
- Adapters, fallbacks, aliases, wrappers, or translation seams that preserve the old path as a second effective mainline are forbidden.
- If callers outside this repository depend on the old path, surface that dependency to the user before proceeding.
- This rule does not authorize broad redesign. If the replacement mainline/source of truth is not already clear from code and active docs, stop and ask.
- Scope is limited to the deprecated symbol, its direct in-repo callers, and the tests/docs asserting that seam.
- Never extend, route new code through, or add features to deprecated or legacy seams.

Tests:

- `Invariant`: product correctness. Fix code, not the test.
- `Contract`: repo-external public surface. Breaking it requires explicit user approval.
- `Snapshot`: implementation detail. It may be updated or removed with explicit justification.
- A failing test is never a reason to add a compatibility layer.
- Never silently skip, disable, or weaken tests to preserve momentum.

Planning for multi-file or core changes:

- Required fields: scope, out of scope, files and symbols, alternative considered, costs, test impact with classifications, risk, verification.
- Approved plans are frozen. If reality materially diverges in scope, contract impact, or risk, stop and report before continuing.

Deprecation and pushback:

- Marking a symbol deprecated requires, in the same change, a `DEPRECATION_LEDGER.md` entry with symbol, replacement, in-repo call-site count, and removal condition.
- Deprecation is not complete until migration of in-repo callers has started in the same change.
- If a request conflicts with these guardrails or knowingly creates structural debt, object once in plain terms, then proceed only if the user confirms.

## 4. Highest-Priority Guardrails

- Do not shrink the product definition back into a phone/group-control system.
- Do not let existing transport-specific names redefine the kernel; new cross-adapter boundaries should stay transport-neutral.
- Do not define a worker as "a WebSocket client"; define it as an executor that can receive tasks, return results, and emit system events through some transport.
- `Task.sharedConfig` and `TaskMsg.input/output` are the generic payload boundaries.
- `target` is only a conventional key inside `TaskMsg.input`, not a model field.
- `POST /status/api/tasks` is the only task-create HTTP route.
- `eventCode` is globally unique capability identity across the runtime catalog.
- Worker runtime event capability truth is `supportedEventCodes`; `supportedProjects` is only a coarse filter hint.
- Routing truth comes from explicit rules and worker-context signals, not `workerGroupId`.
- Keep transport-specific shapes behind `xa-mass-transport-api`; WebSocket payloads must not become kernel truth.
- Do not add scan-heavy observability or reconciliation loops to hot paths; prefer logs, trace, counters, and indexed lookups.
- Bias transport and lifecycle writes toward idempotent operations and explicit retry safety.
- Read [transport/AGENTS.md](transport/AGENTS.md) before changing `transport/transport_api`, `transport/transport_runtime`, or adding a new transport adapter.
- Read [transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md](transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md) before changing `xa-mass-transport-websocket` or `xa-mass-transport-api`.
- Manual worker debug/control is a side-channel and must not mutate task lifecycle state.

## 5. Working Defaults

- Verify the current code path before changing behavior.
- Prefer logs, traces, and bounded diagnostics over model-coupled realtime observability.
- Prefer E2E or integration coverage for lifecycle changes.
- When lifecycle semantics change, update [doc/STATE_MACHINE_BASELINE.md](doc/STATE_MACHINE_BASELINE.md), [doc/TRACE_CONTRACT.md](doc/TRACE_CONTRACT.md), and [doc/E2E_BASELINE.md](doc/E2E_BASELINE.md) together.
- Check [DEPRECATION_LEDGER.md](DEPRECATION_LEDGER.md) before extending any compatibility or legacy seam.
- Keep docs concise and current; delete stale notes instead of preserving parallel narratives.
- Do not recreate removed archive/v2 code.
