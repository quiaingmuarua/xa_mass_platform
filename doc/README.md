# Documentation Index

This directory holds active project-level docs only.

Use [../AGENTS.md](../AGENTS.md) for the canonical repo-root handoff and read order.
Use this page only as an index once you are already inside `doc/`.

## 1. What Each File Is For

- [../README.md](../README.md)
  - shortest project summary
- [../DEPRECATION_LEDGER.md](../DEPRECATION_LEDGER.md)
  - repo-level deprecated and compatibility seams
- [AGENT_BASELINE.md](./AGENT_BASELINE.md)
  - mainline baseline and guardrails
- [TESTING_BASELINE.md](./TESTING_BASELINE.md)
  - cross-module testing policy and lane map
- [STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md)
  - lifecycle vocabulary and invariants
- [TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
  - required trace surface
- [E2E_BASELINE.md](./E2E_BASELINE.md)
  - release-gate E2E scope
- [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
  - verified startup and regression commands
- [INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md)
  - current HTTP/API contracts
- [../xa-mass-engine/README.md](../xa-mass-engine/README.md)
  - engine owner README
- [../xa-mass-engine/POLICY_INTERACTION_BASELINE.md](../xa-mass-engine/POLICY_INTERACTION_BASELINE.md)
  - engine policy interaction guardrails
- [../xa-mass-engine/TASK_EXECUTION_FLOW.md](../xa-mass-engine/TASK_EXECUTION_FLOW.md)
  - engine dispatch/result flow
- [../xa-mass-testing/README.md](../xa-mass-testing/README.md)
  - testing owner README
- [../xa-mass-server/README.md](../xa-mass-server/README.md)
  - server owner README
- [../transport/AGENTS.md](../transport/AGENTS.md)
  - transport owner entry
- [../transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md](../transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md)
  - websocket adapter boundary
- [../transport/TRANSPORT_BOUNDARY_BASELINE.md](../transport/TRANSPORT_BOUNDARY_BASELINE.md)
  - transport boundary
- [../transport/TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md](../transport/TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md)
  - design-only transport throughput reference

## 2. Rules

This directory should stay small and high-signal.

Transport owner docs now live under `../transport/` so protocol/runtime details do not bloat the core project baseline directory.
Module-local test and runner details should live under the owning module, not under `doc/`.

Keep these rules:

- Prefer one stable document per concern, not multiple near-duplicates.
- Update the owning contract document in the same change when code changes a documented contract, ownership rule, or accepted workflow.
- Prefer summary + pointers over long changelog-style repetition.
- Do not reintroduce archive-style history dumps into `doc/`.
- Historical notes belong in a short changelog only when they explain a current operational constraint; otherwise delete them.
- Keep document navigation shallow: headings should usually stop at two levels unless the topic is intrinsically reference-shaped.
- If a fact already lives normatively in one document, other documents should reference it rather than restating it in full.
