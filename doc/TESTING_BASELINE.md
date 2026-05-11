# Testing Baseline

Last updated: 2026-05-11

Status: current global testing baseline.

System-level map of the testing lanes.

Use [TESTING_INDEX.md](./TESTING_INDEX.md) as the default entry for current CI
truth, current asset map, and change-type minimum verification. This file keeps
only the cross-module lane model and placement rules.

Use with:

- [./TESTING_INDEX.md](./TESTING_INDEX.md)
- [./VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
- [./E2E_BASELINE.md](./E2E_BASELINE.md)
- [./TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [../xa-mass-engine/README.md](../xa-mass-engine/README.md)
- [../xa-mass-testing/README.md](../xa-mass-testing/README.md)
- [../xa-mass-server/README.md](../xa-mass-server/README.md)

## 1. Core Rule

- test decisions are organized around the current mainline:
  `project -> submitter / worker capability -> task shell -> item append -> engine runtime -> transport delivery -> result ingest -> convergence`
- core proof is mainline-first: `Boot-shell E2E + engine concurrency/acceptance + cross-language black-box`
- `project` is a mainline business boundary, not only a metadata/resource surface
- `transport` is an explicit validation boundary, not an engine implementation detail
- perf and chaos are part of the project-level test estate, but current CI gate
  truth belongs to [TESTING_INDEX.md](./TESTING_INDEX.md)

## 2. Lane Map

| Lane | Owner | Weight / placement |
| --- | --- | --- |
| `mainline boundary` | `xa-mass-server` | `project / submitter / worker / workerContext` boundary proof on real host surfaces |
| `engine kernel` | `xa-mass-engine` | lifecycle, retry, expiry, finality, release, convergence invariants |
| `transport boundary` | `transport/*`, `xa-mass-testing`, `xa-mass-server` | adapter routing, result ingress, and transport/engine decoupling proof |
| `Boot-shell E2E` | `xa-mass-server` | primary end-to-end acceptance surface for the real mainline |
| `cross-language black-box` | `xa-mass-server` | external worker compatibility across Java / Node and multiple adapters |
| `perf / chaos` | `xa-mass-testing` | scale, recovery, disconnect, replay, and degraded-condition proof |
| `local invariant / module` | owning module tests | support coverage only when it adds kernel or boundary debugging value |

## 3. Command Ownership

- current minimum verification and CI truth: [TESTING_INDEX.md](./TESTING_INDEX.md)
- startup, smoke, and focused regression commands: [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
- engine race/refill/release coverage: [../xa-mass-engine/README.md](../xa-mass-engine/README.md)
- perf, SDK harness, and chaos: [../xa-mass-testing/README.md](../xa-mass-testing/README.md)
- Boot-shell E2E suite map: [../xa-mass-server/README.md](../xa-mass-server/README.md)
- external worker sample lane: `./scripts/run-external-worker-samples.sh`

## 4. Lane Intent

- `mainline boundary` verifies project, submitter, worker, and worker-context
  ownership, auth, and capability boundaries at the real host edge
- `engine kernel` verifies lifecycle and convergence invariants that are easier
  to prove deterministically under concurrency than through the host shell
- `transport boundary` verifies routing, result ingress, and decoupling so
  transport does not redefine kernel semantics
- `Boot-shell E2E` is the default proof surface for integrated mainline changes
- `cross-language black-box` proves external worker compatibility across
  process and language boundaries
- `perf / chaos` proves scale and degraded-condition resilience; it does not
  replace ordinary feature acceptance

For change-type specific minimum verification, use
[TESTING_INDEX.md](./TESTING_INDEX.md).

## 5. Fast Path

Identify the dominant boundary first:

- `xa-mass-server` for mainline boundary and Boot-shell E2E
- `xa-mass-engine` for lifecycle, retry, expiry, release, and convergence
- `transport/*` plus `xa-mass-testing` for transport runtime, routing, perf, and chaos

Read the owner README after [TESTING_INDEX.md](./TESTING_INDEX.md) confirms the
minimum verification set.

## 6. Documentation Rule

- this file answers cross-module testing questions only
- detailed perf, concurrency, chaos, and suite maps belong in owner READMEs
- `doc/` should not accumulate module-local testing playbooks
- [TESTING_INDEX.md](./TESTING_INDEX.md) is the only default entry for current
  CI truth, current suite map, and minimum verification rules
- [E2E_BASELINE.md](./E2E_BASELINE.md) and
  [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md) stay project-level because they
  define release-scope semantics and verified runtime behavior
