# Documentation Index

Status: current documentation index.

Use [../AGENTS.md](../AGENTS.md) for the fast repo-root handoff. Use this page
only when you need the expanded reading map inside `doc/`.

## 1. Fast Paths

Most tasks only need one contract lane plus one owner README:

- human onboarding / project architecture:
  [../architecture/README.md](../architecture/README.md)
- lifecycle / trace / E2E:
  [STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md),
  [TRACE_CONTRACT.md](./TRACE_CONTRACT.md),
  [E2E_BASELINE.md](./E2E_BASELINE.md)
- trace operator / trace-observed verification:
  [../xa-mass-trace/README.md](../xa-mass-trace/README.md),
  [TRACE_CONTRACT.md](./TRACE_CONTRACT.md),
  [TESTING_INDEX.md](./TESTING_INDEX.md)
- result owner split / runtime result truth:
  [RESULT_BOUNDARY_BASELINE.md](./RESULT_BOUNDARY_BASELINE.md)
- storage / runtime / trace placement:
  [INFRA_TRUTH_LAYERS.md](./INFRA_TRUTH_LAYERS.md),
  [DB_STORAGE_PRINCIPLES.md](./DB_STORAGE_PRINCIPLES.md),
  [../platform_infra/README.md](../platform_infra/README.md)
- testing / acceptance:
  [TESTING_INDEX.md](./TESTING_INDEX.md),
  [TESTING_BASELINE.md](./TESTING_BASELINE.md),
  [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
- HTTP / external shell:
  [INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md),
  [EXTERNAL_WORKER_QUICKSTART.md](./EXTERNAL_WORKER_QUICKSTART.md)

## 2. Core Global Docs

| File | Purpose |
| --- | --- |
| [AGENT_BASELINE.md](./AGENT_BASELINE.md) | global platform baseline and hard guardrails |
| [STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md) | lifecycle vocabulary and invariants |
| [TRACE_CONTRACT.md](./TRACE_CONTRACT.md) | required trace surface |
| [RESULT_BOUNDARY_BASELINE.md](./RESULT_BOUNDARY_BASELINE.md) | runtime result owner split, public result boundary, and compatibility residue rules |
| [INFRA_TRUTH_LAYERS.md](./INFRA_TRUTH_LAYERS.md) | control-plane/runtime/trace placement matrix |
| [DB_STORAGE_PRINCIPLES.md](./DB_STORAGE_PRINCIPLES.md) | DB boundary and hot-write guardrail |
| [TESTING_INDEX.md](./TESTING_INDEX.md) | current testing entry, CI truth, and minimum verification map |
| [TESTING_BASELINE.md](./TESTING_BASELINE.md) | acceptance lanes and test matrix |
| [E2E_BASELINE.md](./E2E_BASELINE.md) | Boot-shell E2E scope |
| [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md) | verified startup and regression commands |
| [INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md) | current HTTP/API contract |
| [HIGH_VOLUME_MODEL_BASELINE.md](./HIGH_VOLUME_MODEL_BASELINE.md) | high-volume runtime facts and guardrails |

## 3. Owner Docs

Use owner docs for module-local truth:

- engine: [../xa-mass-engine/README.md](../xa-mass-engine/README.md)
- trace operator: [../xa-mass-trace/README.md](../xa-mass-trace/README.md)
- transport: [../transport/AGENTS.md](../transport/AGENTS.md)
- infra: [../platform_infra/README.md](../platform_infra/README.md)
- testing: [../xa-mass-testing/README.md](../xa-mass-testing/README.md)
- server: [../xa-mass-server/README.md](../xa-mass-server/README.md)
- worker pack: [../xa-mass-worker-pack/README.md](../xa-mass-worker-pack/README.md)
- SDK API: [../xa-mass-sdk-api/README.md](../xa-mass-sdk-api/README.md)
- SDK embedding: [../xa-mass-sdk/README.md](../xa-mass-sdk/README.md)

## 4. Design-Only References

These are useful only when the task explicitly touches those future directions:

- [ARCHITECTURE_BOUNDARY_DIRECTION.md](./ARCHITECTURE_BOUNDARY_DIRECTION.md)
- [../xa-mass-engine/doc/roadmap/TASK_RUNTIME_PROFILE_DESIGN.md](../xa-mass-engine/doc/roadmap/TASK_RUNTIME_PROFILE_DESIGN.md)
- [../transport/TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md](../transport/TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md)

## 5. Historical Archive

Archived documents are changelog-style historical context only. Do not use them
as proof of current implementation behavior; verify against current code,
tests, owner READMEs, and baseline docs.

- archive index: [archive/README.md](./archive/README.md)
- engine: [archive/xa-mass-engine/](./archive/xa-mass-engine/)

## 6. What Stays Out Of `doc/`

Do not add a new `doc/*` file for:

- human-facing architecture onboarding guides
- module-local implementation notes
- module-local test inventories or command lists
- adapter-specific protocol behavior
- one-module design/refactor notes
- migration inventory owned by one module

Those belong in the owning module.
