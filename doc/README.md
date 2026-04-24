# Documentation Index

This directory holds the active, non-archived project documents.

Use [../AGENTS.md](../AGENTS.md) for the canonical repo-root handoff and read order.
Use this page as the directory index and owner map once you are already inside `doc/`.

## 1. What Each File Is For

- [../README.md](../README.md)
  - The shortest product-positioning summary: what the platform is now and what direction the current mainline is converging toward.
- [../DEPRECATION_LEDGER.md](../DEPRECATION_LEDGER.md)
  - Single repo-level index of deprecated, compatibility, and legacy seams that still exist in active paths.
- [AGENT_BASELINE.md](./AGENT_BASELINE.md)
  - Stable project baseline for agents: platform definition, mainline goals, guardrails, active module truth, payload contract, and lifecycle baseline.
- [GATEWAY_BOUNDARY_BASELINE.md](./GATEWAY_BOUNDARY_BASELINE.md)
  - High-density contract for gateway ownership, transport-neutral SPI, worker-runtime execution ownership, unified lifecycle semantics, and forbidden drift.
- [STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md)
  - Normative state vocabulary and invariants for `Task`, `TaskMsg`, `WorkerContext`, and `TaskTerminalReason`.
- [TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
  - Minimum required structured trace surface for lifecycle debugging and replayability.
- [E2E_BASELINE.md](./E2E_BASELINE.md)
  - Short release-gate baseline for what must be covered by active-mainline E2E tests and which transport paths are validated through SDK integration versus Boot-shell E2E.
- [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
  - Verified startup path, runtime checks, and current execution conclusions.
- [INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md)
  - Current HTTP/API inventory, request contract, response shape, and implementation status.
- [INTEGRATION_TESTS.md](./INTEGRATION_TESTS.md)
  - Practical guide to the grouped `xa-mass-dev-app` integration suites: structure, coverage map, patterns, and current gaps.
- [engine/POLICY_INTERACTION_BASELINE.md](./engine/POLICY_INTERACTION_BASELINE.md)
  - Guardrails for preventing combinatorial policy interactions across matching, retry, release, refill, intake, control, and terminal decisions.
- [engine/TASK_EXECUTION_FLOW.md](./engine/TASK_EXECUTION_FLOW.md)
  - Task execution flow through matching, dispatch, callback write-back, and resource release.

## 2. Reading Shortcuts

- entering from repo root: start at [../AGENTS.md](../AGENTS.md)
- checking legacy/compatibility/deprecation work: go to [../DEPRECATION_LEDGER.md](../DEPRECATION_LEDGER.md)
- changing gateway or transport code: go to [GATEWAY_BOUNDARY_BASELINE.md](./GATEWAY_BOUNDARY_BASELINE.md)
- changing lifecycle semantics: go to [STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md), [TRACE_CONTRACT.md](./TRACE_CONTRACT.md), and [E2E_BASELINE.md](./E2E_BASELINE.md)
- changing HTTP/API contracts: go to [INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md)
- changing startup or runtime verification: go to [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
- changing policy ownership/interactions: go to [engine/POLICY_INTERACTION_BASELINE.md](./engine/POLICY_INTERACTION_BASELINE.md)
- tracing dispatch/result flow: go to [engine/TASK_EXECUTION_FLOW.md](./engine/TASK_EXECUTION_FLOW.md)

## 3. Current Compression Rule

This directory should stay small and high-signal.

Keep these rules:

- Prefer one stable document per concern, not multiple near-duplicates.
- Prefer summary + pointers over long changelog-style repetition.
- Do not reintroduce archive-style history dumps into `doc/`.
- Historical notes belong in a short changelog only when they explain a current operational constraint; otherwise delete them.
- Keep document navigation shallow: headings should usually stop at two levels unless the topic is intrinsically reference-shaped.
- If a fact already lives normatively in one document, other documents should reference it rather than restating it in full.
