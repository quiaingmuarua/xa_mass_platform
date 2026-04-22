# Documentation Index

This directory holds the active, non-archived project documents.

Use this page as the entry point before opening individual files.

## 1. Read Order

Recommended order for a new maintainer or coding agent:

1. [../AGENTS.md](../AGENTS.md)
2. [AGENT_BASELINE.md](./AGENT_BASELINE.md)
3. [STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md)
4. [TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
5. [E2E_BASELINE.md](./E2E_BASELINE.md)
6. [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
7. [INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md)
8. [INTEGRATION_TESTS.md](./INTEGRATION_TESTS.md)
9. [engine/POLICY_INTERACTION_BASELINE.md](./engine/POLICY_INTERACTION_BASELINE.md)
10. [engine/TASK_EXECUTION_FLOW.md](./engine/TASK_EXECUTION_FLOW.md)

## 2. What Each File Is For

- [../README.md](../README.md)
  - The shortest product-positioning summary: what the platform is now and what direction the current mainline is converging toward.
- [AGENT_BASELINE.md](./AGENT_BASELINE.md)
  - Stable project baseline for agents: platform definition, mainline goals, guardrails, active module truth, payload contract, and lifecycle baseline.
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

## 3. Current Compression Rule

This directory should stay small and high-signal.

Keep these rules:

- Prefer one stable document per concern, not multiple near-duplicates.
- Prefer summary + pointers over long changelog-style repetition.
- Do not reintroduce archive-style history dumps into `doc/`.
- Historical notes belong in a short changelog only when they explain a current operational constraint; otherwise delete them.
- Keep document navigation shallow: headings should usually stop at two levels unless the topic is intrinsically reference-shaped.
- If a fact already lives normatively in one document, other documents should reference it rather than restating it in full.
