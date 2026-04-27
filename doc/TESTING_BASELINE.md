# Testing Baseline

Last updated: 2026-04-27

System-level map of the testing lanes. Use module READMEs for concrete commands
and suite inventories.

Use with:

- [./VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
- [./E2E_BASELINE.md](./E2E_BASELINE.md)
- [./TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [../xa-mass-engine/README.md](../xa-mass-engine/README.md)
- [../xa-mass-testing/README.md](../xa-mass-testing/README.md)
- [../xa-mass-dev-app/README.md](../xa-mass-dev-app/README.md)

## 1. Core Rule

- core acceptance is `Boot-shell E2E + concurrency + perf`
- `cross-language sample black-box` is also core for external worker compatibility
- `chaos` is scheduled/manual, not a default PR-required lane
- acceptance bias is throughput, HA recovery, and idempotent behavior

## 2. Lane Map

| Lane | Primary job | Owner / entry | Weight |
| --- | --- | --- | --- |
| `invariant` | prove one local rule or state transition | owning module tests | support |
| `module` | guard one module boundary or helper contract | owning module tests | support |
| `SDK embedded harness` | exercise real SDK registration/runtime composition without Boot shell | `xa-mass-testing` | support |
| `Boot-shell E2E` | prove the real runtime path through `xa-mass-dev-app` | `xa-mass-dev-app` | core |
| `cross-language sample black-box` | prove external worker references stay runnable and scheduler-correct | `xa-mass-dev-app` | core |
| `concurrency` | prove race-heavy paths converge to an allowed stable state | `xa-mass-engine` | core |
| `perf` | expose hot-path cost, storage pressure, and throughput regressions | `xa-mass-testing` | core |
| `chaos` | prove degraded/recovery behavior under disruption | `xa-mass-testing` | scheduled robustness |

## 3. Command Ownership

- startup, smoke, and focused regression commands: [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
- engine race/refill/release coverage: [../xa-mass-engine/README.md](../xa-mass-engine/README.md)
- perf, SDK harness, and chaos: [../xa-mass-testing/README.md](../xa-mass-testing/README.md)
- Boot-shell E2E suite map: [../xa-mass-dev-app/README.md](../xa-mass-dev-app/README.md)
- external worker sample lane: `./scripts/run-external-worker-samples.sh`

## 4. CI Placement

| Lane | Placement | Why |
| --- | --- | --- |
| `invariant` / `module` | PR required | cheap, deterministic, high signal |
| focused `Boot-shell E2E` | PR required | mainline runtime acceptance must stay green |
| `cross-language sample black-box` | PR required + nightly | guards public worker references against in-repo shortcuts |
| deterministic `concurrency` subset | PR required when the touched path is race-sensitive | catches release/finalize/callback ordering regressions early |
| `perf` smoke | PR optional or non-blocking | useful signal, but CI variance should not block every PR |
| heavier `concurrency` matrix | nightly or release | broader race matrices cost more and are noisier |
| heavier `perf` baseline/trend checks | nightly or release | throughput thresholds need steadier cadence |
| `chaos` | nightly, manual, or release | valuable but usually too noisy for PR-required gates |

Current workflow labels from [../.github/workflows/maven.yml](../.github/workflows/maven.yml), [../.github/workflows/external-worker-samples.yml](../.github/workflows/external-worker-samples.yml), and [../.github/workflows/perf-smokes.yml](../.github/workflows/perf-smokes.yml):

- `build`: broad `./mvnw -B test`
- `lifecycle-integration`: focused `xa-mass-dev-app` lifecycle integration subset
- `cross-language-blackbox`: external worker sample regression on PR/push plus scheduled daily run
- `perf-smokes`: scheduled/manual perf smoke bundle with artifact upload

## 5. Change-Type Matrix

| Change type | Minimum lanes | Also verify |
| --- | --- | --- |
| callback/result handling | `concurrency` + `Boot-shell E2E` | [TRACE_CONTRACT.md](./TRACE_CONTRACT.md) |
| retry / logical-message finality | `concurrency` + `Boot-shell E2E` | state-machine and trace coverage |
| worker release / worker-context release | `concurrency` + `Boot-shell E2E` | engine README + trace coverage |
| assignment refill / batching / gate semantics | `Boot-shell E2E` + targeted `concurrency` | `perf` if the path is hot |
| hot-path storage or counters | `perf` + targeted `concurrency` | one E2E smoke if external behavior could drift |
| lifecycle API/controller behavior | `Boot-shell E2E` | invariant/module tests at the controller edge |
| resilience under disconnect, delay, restart, drop | `chaos` once available | at least one deterministic concurrency surrogate first |

## 6. Fast Path

When analyzing one risk:

1. identify the owning module
2. use `xa-mass-engine` for races and refill
3. use `xa-mass-testing` for perf, SDK harness, and chaos
4. use `xa-mass-dev-app` for Boot-shell E2E suite detail
5. only then fan out into broader trace or project-level docs

## 7. Documentation Rule

- this file answers cross-module testing questions only
- detailed perf, concurrency, chaos, and suite maps belong in owner READMEs
- `doc/` should not accumulate module-local testing playbooks
- [E2E_BASELINE.md](./E2E_BASELINE.md) and [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md) stay project-level because they define release-gate scope and verified runtime behavior
