# Documentation Index

Status: current documentation index.

Use [../AGENTS.md](../AGENTS.md) for the fast repo-root handoff. This directory
keeps only global facts and constraints that help an agent understand the
current mainline quickly.

## 1. Core Global Docs

Read the fewest files possible:

| File | Purpose |
| --- | --- |
| [AGENT_BASELINE.md](./AGENT_BASELINE.md) | global platform baseline and hard guardrails |
| [STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md) | lifecycle vocabulary and invariants |
| [RESULT_BOUNDARY_BASELINE.md](./RESULT_BOUNDARY_BASELINE.md) | runtime result owner split, public result boundary, and compatibility residue rules |
| [INFRA_TRUTH_LAYERS.md](./INFRA_TRUTH_LAYERS.md) | control-plane/runtime/trace placement matrix plus DB hot-write guardrails |
| [TRACE_CONTRACT.md](./TRACE_CONTRACT.md) | required trace surface |
| [TESTING_INDEX.md](./TESTING_INDEX.md) | current proof lanes, E2E boundary, CI truth, and minimum verification map |
| [PROOF_REGISTRY.md](./PROOF_REGISTRY.md) | authoritative proof ownership and representative trace pairing |
| [SDK_INTEGRATIONS_BOUNDARY_GUARD.md](./SDK_INTEGRATIONS_BOUNDARY_GUARD.md) | guardrails for SDK modules, public-contract DTOs, integrations, worker-pack, samples, and server startup registration |

## 2. Owner Docs

Use owner docs for module-local truth:

- engine: [../xa-mass-engine/README.md](../xa-mass-engine/README.md)
- trace operator: [../xa-mass-trace/README.md](../xa-mass-trace/README.md)
- transport: [../transport/AGENTS.md](../transport/AGENTS.md)
- infra: [../platform_infra/README.md](../platform_infra/README.md)
- worker runtime: [../xa-mass-worker-runtime/README.md](../xa-mass-worker-runtime/README.md),
  [../xa-mass-worker-runtime/CONTRACTS.md](../xa-mass-worker-runtime/CONTRACTS.md)
- testing: [../xa-mass-testing/README.md](../xa-mass-testing/README.md)
- server: [../xa-mass-server/README.md](../xa-mass-server/README.md)
- SDK directory: [../sdk/README.md](../sdk/README.md)
- integrations directory: [../integrations/README.md](../integrations/README.md)
- worker pack: [../integrations/xa-mass-worker-pack/README.md](../integrations/xa-mass-worker-pack/README.md)
- public HTTP contract: [../sdk/xa-mass-public-contract/README.md](../sdk/xa-mass-public-contract/README.md)
- external Java SDK: [../sdk/xa-mass-java-sdk/README.md](../sdk/xa-mass-java-sdk/README.md)
- embedded SDK API: [../sdk/xa-mass-embedded-sdk-api/README.md](../sdk/xa-mass-embedded-sdk-api/README.md)
- embedded SDK runtime: [../sdk/xa-mass-embedded-sdk/README.md](../sdk/xa-mass-embedded-sdk/README.md)
- verified runbook: [../xa-mass-testing/VERIFIED_RUNBOOK.md](../xa-mass-testing/VERIFIED_RUNBOOK.md)
- HTTP/API reference: [../xa-mass-server/doc/INTERNAL_API_REFERENCE.md](../xa-mass-server/doc/INTERNAL_API_REFERENCE.md)
- external worker quickstart: [../sdk/xa-mass-java-sdk/EXTERNAL_WORKER_QUICKSTART.md](../sdk/xa-mass-java-sdk/EXTERNAL_WORKER_QUICKSTART.md)
- high-volume runtime facts: [../xa-mass-engine/doc/baseline/HIGH_VOLUME_MODEL_BASELINE.md](../xa-mass-engine/doc/baseline/HIGH_VOLUME_MODEL_BASELINE.md)

## 3. Roadmaps And Direction

Active roadmap, inventory, and decision documents live under
[../roadmap/](../roadmap/). This root index intentionally does not list each
roadmap; use filename search or `rg` when a task touches planned convergence or
future direction.

Roadmap execution records should live outside `doc/` until they are archived.
Do not use roadmap prose as proof of current implementation without verifying
code, tests, and owner README files.

## 4. Historical Archive

Archived documents are changelog-style historical context only. Do not use them
as proof of current implementation behavior; verify against current code,
tests, owner READMEs, and baseline docs.

Archived docs are intentionally not part of the active reading map. Use the
archive only for historical audits, changelog reconstruction, or residue scans.

## 5. Reusable Codex Skills

Reusable agent skills that can be installed remotely live under
[skills/](./skills/). These are workflow assets, not platform behavior
contracts.

- [roadmap-refinement](./skills/roadmap-refinement/SKILL.md) - roadmap owner
  review, refinement, portfolio classification, and slice execution rules
- [roadmap-residue-scan](./skills/roadmap-residue-scan/SKILL.md) - post-roadmap
  residue, stale status, old-name, and compatibility-path scans

## 6. What Stays Out Of `doc/`

Do not add a new `doc/*` file for:

- human-facing architecture onboarding guides
- module-local implementation notes
- module-local test inventories or command lists
- adapter-specific protocol behavior
- one-module design/refactor notes
- migration inventory owned by one module
- API dictionaries, runbooks, or quickstarts owned by a module
- roadmap, inventory, decision, or direction documents

Those belong in the owning module or top-level `roadmap/`.
