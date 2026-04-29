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
| [INFRA_TRUTH_LAYERS.md](./INFRA_TRUTH_LAYERS.md) | dense control-plane/runtime/trace placement contract |
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

## 2. Keep In `doc/`

These files are appropriate at the global `doc/` level because they define
cross-module contracts, repo-level verification, or operator-facing reference
surfaces:

| Category | Files |
| --- | --- |
| global baseline / contract | `AGENT_BASELINE.md`, `INFRA_TRUTH_LAYERS.md`, `STATE_MACHINE_BASELINE.md`, `TRACE_CONTRACT.md`, `DB_STORAGE_PRINCIPLES.md` |
| global verification / reference | `TESTING_BASELINE.md`, `E2E_BASELINE.md`, `VERIFIED_RUNBOOK.md`, `INTERNAL_API_REFERENCE.md`, `EXTERNAL_WORKER_QUICKSTART.md` |
| global index / status | `README.md`, `CURRENT_GAPS.md` |
| global design reference | `HIGH_VOLUME_MODEL_BASELINE.md` |

## 3. Compression Policy

`doc/` can stay small, but do not compress by merging distinct contracts into
one long narrative. Compress by reducing overlap and by refusing new global docs
unless they are truly cross-module.

Preferred compression rules:

- keep one stable doc per global concern
- move module-owned detail into the owning module README instead of adding a new
  `doc/*` file
- shorten repeated explanations in baseline docs and replace them with links to
  the owning contract
- keep indexes, checklists, and matrices dense; keep examples and local details
  near the owning module

Current recommendation:

- keep the current global set
- do not merge `STATE_MACHINE_BASELINE.md`, `TRACE_CONTRACT.md`, and
  `DB_STORAGE_PRINCIPLES.md`; they are adjacent but not the same concern
- do not merge `TESTING_BASELINE.md`, `E2E_BASELINE.md`, and
  `VERIFIED_RUNBOOK.md`; they answer different questions
- if `INTERNAL_API_REFERENCE.md` grows further, split by API family only if the
  split still stays under the same global HTTP contract surface

## 4. Move Out Of `doc/`

A file should move out of `doc/` when it becomes primarily:

- module-local behavior or implementation detail
- module-local test inventory or command list
- adapter-specific protocol behavior
- design/refactor notes owned by one module
- migration inventory that only one owner module needs

In those cases, keep a short global pointer here and move the actual content to
the owning module.

## 5. Rules

- `doc/` holds global/cross-module introductions, contracts, constraints, indexes, and verified runbooks.
- Module-local tests, runners, design notes, and refactor notes live under the owning module; `doc/` may index cross-module-impacting design notes.
- Prefer one stable document per concern; reference normative facts instead of restating them.
- Mark status explicitly: current baseline/contract, gap index, design/refactor reference, or migration inventory.
- Delete stale history; keep short changelog notes only when they explain a live operational constraint.
