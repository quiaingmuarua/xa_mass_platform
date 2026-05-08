# Testing Baseline

Last updated: 2026-04-27

Status: current global testing baseline.

System-level map of the testing lanes. Use module READMEs for concrete commands
and suite inventories.

Use with:

- [./VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
- [./E2E_BASELINE.md](./E2E_BASELINE.md)
- [./TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [../xa-mass-engine/README.md](../xa-mass-engine/README.md)
- [../xa-mass-testing/README.md](../xa-mass-testing/README.md)
- [../xa-mass-server/README.md](../xa-mass-server/README.md)

## 1. Core Rule

- core acceptance is `Boot-shell E2E + concurrency + perf`
- `cross-language sample black-box` is also core for external worker compatibility
- `chaos` is scheduled/manual, not a default PR-required lane
- acceptance bias is throughput, HA recovery, and idempotent behavior

## 2. Lane Map

| Lane | Owner | Weight / placement |
| --- | --- | --- |
| `invariant` / `module` | owning module tests | PR support coverage |
| `SDK embedded harness` | `xa-mass-testing` | support, fast transport-aware probe |
| `Boot-shell E2E` | `xa-mass-server` | core, PR required focused subset |
| `cross-language sample black-box` | `xa-mass-server` | core, PR + nightly |
| `concurrency` | `xa-mass-engine` | core when race-sensitive; broader matrix nightly/release |
| `perf` | `xa-mass-testing` | core signal; smoke optional/non-blocking, trend nightly/release |
| `chaos` | `xa-mass-testing` | scheduled/manual/release robustness |

## 3. Command Ownership

- startup, smoke, and focused regression commands: [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
- engine race/refill/release coverage: [../xa-mass-engine/README.md](../xa-mass-engine/README.md)
- perf, SDK harness, and chaos: [../xa-mass-testing/README.md](../xa-mass-testing/README.md)
- Boot-shell E2E suite map: [../xa-mass-server/README.md](../xa-mass-server/README.md)
- external worker sample lane: `./scripts/run-external-worker-samples.sh`

## 4. Change-Type Matrix

| Change type | Read first | Owning surface | Minimum verification |
| --- | --- | --- | --- |
| task lifecycle or state transitions | [STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md), [TRACE_CONTRACT.md](./TRACE_CONTRACT.md), [E2E_BASELINE.md](./E2E_BASELINE.md) | `xa-mass-engine`, then `xa-mass-server` E2E | `concurrency` + `Boot-shell E2E` |
| callback/result handling | [STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md), [TRACE_CONTRACT.md](./TRACE_CONTRACT.md) | engine result ingest and transport result channels | `concurrency` + `Boot-shell E2E` |
| retry / logical-message finality | [STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md), [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md) | `TaskWorkRuntime`, `TaskManager`, attempt/result services | `concurrency` + `Boot-shell E2E` |
| worker release / worker-context release | [../xa-mass-engine/README.md](../xa-mass-engine/README.md), [TRACE_CONTRACT.md](./TRACE_CONTRACT.md) | engine resource release listeners and worker manager | `concurrency` + `Boot-shell E2E` |
| assignment refill / batching / gate semantics | [../xa-mass-engine/README.md](../xa-mass-engine/README.md), [../xa-mass-server/README.md](../xa-mass-server/README.md) | assignment listener, matching strategy, Boot-shell E2E | `Boot-shell E2E` + targeted `concurrency`; add `perf` if hot |
| external polling worker API | [INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md), [EXTERNAL_WORKER_QUICKSTART.md](./EXTERNAL_WORKER_QUICKSTART.md), [../xa-mass-server/README.md](../xa-mass-server/README.md) | `/worker-api/v1/**`, SDK external worker operations | external worker black-box + `Boot-shell E2E` |
| transport adapter/runtime boundary | [../transport/AGENTS.md](../transport/AGENTS.md), [../transport/TRANSPORT_BOUNDARY_BASELINE.md](../transport/TRANSPORT_BOUNDARY_BASELINE.md) | `transport/*`, SDK transport composition | transport module tests + `Boot-shell E2E` |
| hot-path storage or counters | [HIGH_VOLUME_MODEL_BASELINE.md](./HIGH_VOLUME_MODEL_BASELINE.md), [../xa-mass-testing/README.md](../xa-mass-testing/README.md) | `TaskWorkRuntime`, storage ports, task convergence | `perf` + targeted `concurrency`; add one E2E smoke if external behavior can drift |
| resilience under disconnect, delay, restart, drop | [CURRENT_GAPS.md](./CURRENT_GAPS.md), [../xa-mass-testing/README.md](../xa-mass-testing/README.md), [../xa-mass-server/README.md](../xa-mass-server/README.md) | transport/runtime recovery and engine convergence | deterministic concurrency surrogate first; `chaos` for scheduled/manual proof |

## 5. CI Labels

`build`, `lifecycle-integration`, `cross-language-blackbox`, `perf-smokes`.

## 6. Fast Path

Identify owner module first: engine for races/refill, testing for perf/SDK/chaos, server for Boot-shell E2E. Fan out to broader trace/project docs only after the owner path is clear.

## 7. Documentation Rule

- this file answers cross-module testing questions only
- detailed perf, concurrency, chaos, and suite maps belong in owner READMEs
- `doc/` should not accumulate module-local testing playbooks
- [E2E_BASELINE.md](./E2E_BASELINE.md) and [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md) stay project-level because they define release-gate scope and verified runtime behavior
