# XA Mass Platform Agent Handoff

This is the fastest entry point for coding agents. Keep it short. Use the owner docs under `doc/` for details.

## 0. TL;DR

- XA Mass Platform is a general distributed task scheduling platform.
- Stable kernel: `Task / TaskMsg / TaskMsgAttempt / assignment / result / audit / terminal policy`.
- Transport is three explicit channels: task dispatch, result ingest, and system events.
- WebSocket is the current adapter path, not the product boundary.
- Polling/pull workers are mainline, not side features.
- Project direction is library/SDK-first; HTTP pages and demo APIs are validation shells.
- Real Boot entry is `xa-mass-dev-app`; embedded runtime composition lives in `xa-mass-sdk`.
- Mainline acceptance is integration/E2E first.

## 1. Read Order

1. [README.md](README.md)
2. [doc/README.md](doc/README.md)
3. [doc/AGENT_BASELINE.md](doc/AGENT_BASELINE.md)
4. [doc/GATEWAY_BOUNDARY_BASELINE.md](doc/GATEWAY_BOUNDARY_BASELINE.md)
5. [doc/STATE_MACHINE_BASELINE.md](doc/STATE_MACHINE_BASELINE.md)
6. [doc/TRACE_CONTRACT.md](doc/TRACE_CONTRACT.md)
7. [doc/E2E_BASELINE.md](doc/E2E_BASELINE.md)
8. [doc/VERIFIED_RUNBOOK.md](doc/VERIFIED_RUNBOOK.md)
9. [doc/INTERNAL_API_REFERENCE.md](doc/INTERNAL_API_REFERENCE.md)
10. [doc/INTEGRATION_TESTS.md](doc/INTEGRATION_TESTS.md)
11. [doc/engine/POLICY_INTERACTION_BASELINE.md](doc/engine/POLICY_INTERACTION_BASELINE.md)
12. [doc/engine/TASK_EXECUTION_FLOW.md](doc/engine/TASK_EXECUTION_FLOW.md)

Trust order:

1. code
2. verified runtime behavior
3. this handoff
4. active docs under `doc/`
5. module README files
6. older refactor notes only after re-verification

## 2. Agent Behavior Contract

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

## 3. Highest-Priority Guardrails

- Do not shrink the product definition back into a phone/group-control system.
- `Worker`, `WorkerContext`, and WebSocket are current adapter names, not final universal platform boundaries.
- Do not define a worker as "a WebSocket client"; define it as an executor that can receive tasks, return results, and emit system events through some transport.
- `Task.sharedConfig` and `TaskMsg.input/output` are the generic payload boundaries.
- `target` is only a conventional key inside `TaskMsg.input`, not a model field.
- `POST /status/api/tasks` is the only task-create HTTP route.
- `eventCode` is globally unique capability identity across the runtime catalog.
- Worker runtime event capability truth is `supportedEventCodes`; `supportedProjects` is only a coarse filter hint.
- Routing truth comes from explicit rules and worker-context signals, not `workerGroupId`.
- Keep transport-specific shapes behind `xa-mass-transport-api`; WebSocket payloads must not become kernel truth.
- Read [doc/GATEWAY_BOUNDARY_BASELINE.md](doc/GATEWAY_BOUNDARY_BASELINE.md) before changing `xa-mass-gateway` or `xa-mass-transport-api`.
- Manual worker debug/control is a side-channel and must not mutate task lifecycle state.

## 4. Working Defaults

- Verify the current code path before changing behavior.
- Prefer E2E or integration coverage for lifecycle changes.
- When lifecycle semantics change, update [doc/STATE_MACHINE_BASELINE.md](doc/STATE_MACHINE_BASELINE.md), [doc/TRACE_CONTRACT.md](doc/TRACE_CONTRACT.md), and [doc/E2E_BASELINE.md](doc/E2E_BASELINE.md) together.
- Keep docs concise and current; delete stale notes instead of preserving parallel narratives.
- Do not recreate removed archive/v2 code.
