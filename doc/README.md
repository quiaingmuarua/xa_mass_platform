# Documentation Index

Status: current documentation index.

This directory holds active project-level docs only.

Use [../AGENTS.md](../AGENTS.md) for the canonical repo-root handoff and read order.
Use this page only as an index once you are already inside `doc/`.

## 1. File Map

| File | Purpose |
| --- | --- |
| [../README.md](../README.md) | shortest project summary |
| [../DEPRECATION_LEDGER.md](../DEPRECATION_LEDGER.md) | repo-level deprecated/compatibility seams |
| [AGENT_BASELINE.md](./AGENT_BASELINE.md) | global mainline baseline and guardrails |
| [STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md) | lifecycle vocabulary and invariants |
| [TRACE_CONTRACT.md](./TRACE_CONTRACT.md) | required trace surface |
| [E2E_BASELINE.md](./E2E_BASELINE.md) | release-gate E2E scope |
| [TESTING_BASELINE.md](./TESTING_BASELINE.md) | cross-module testing lanes and change matrix |
| [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md) | verified startup and regression commands |
| [CURRENT_GAPS.md](./CURRENT_GAPS.md) | runtime/coverage gap index |
| [INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md) | current HTTP/API contracts |
| [HIGH_VOLUME_MODEL_BASELINE.md](./HIGH_VOLUME_MODEL_BASELINE.md) | cross-module design/refactor reference, not current truth |
| [../xa-mass-engine/TASK_RUNTIME_PROFILE_DESIGN.md](../xa-mass-engine/TASK_RUNTIME_PROFILE_DESIGN.md) | engine-owned design/refactor reference, not current truth |
| [../xa-mass-engine/README.md](../xa-mass-engine/README.md) | engine owner README |
| [../xa-mass-testing/README.md](../xa-mass-testing/README.md) | testing owner README |
| [../xa-mass-server/README.md](../xa-mass-server/README.md) | server owner README |
| [../xa-mass-worker-pack/README.md](../xa-mass-worker-pack/README.md) | sample/dev worker capability owner README |
| [../transport/AGENTS.md](../transport/AGENTS.md) | transport owner entry |
| [../transport/TRANSPORT_BOUNDARY_BASELINE.md](../transport/TRANSPORT_BOUNDARY_BASELINE.md) | transport boundary |
| [../transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md](../transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md) | websocket adapter boundary |
| [../transport/TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md](../transport/TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md) | transport design/refactor reference, not current truth |

## 2. Rules

- `doc/` holds global/cross-module introductions, contracts, constraints, indexes, and verified runbooks.
- Module-local tests, runners, design notes, and refactor notes live under the owning module; `doc/` may index cross-module-impacting design notes.
- Prefer one stable document per concern; reference normative facts instead of restating them.
- Mark status explicitly: current baseline/contract, gap index, design/refactor reference, or migration inventory.
- Delete stale history; keep short changelog notes only when they explain a live operational constraint.
