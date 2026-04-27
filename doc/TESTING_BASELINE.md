# Testing Baseline

Last updated: 2026-04-27

This is the top-level map of the testing system.

Use it first when you need to answer any of these questions quickly:

- which test layer owns acceptance for this change
- which lane should run in PR CI vs nightly vs release
- where a new agent should start when analyzing one specific runtime risk

Use with:

- [./VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
- [./E2E_BASELINE.md](./E2E_BASELINE.md)
- [./TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [../xa-mass-engine/README.md](../xa-mass-engine/README.md)
- [../xa-mass-testing/README.md](../xa-mass-testing/README.md)
- [../xa-mass-dev-app/README.md](../xa-mass-dev-app/README.md)

## 1. Test Taxonomy

| Layer | Primary job | Typical scope | Acceptance weight |
| --- | --- | --- | --- |
| `invariant` | prove correctness of one local rule or state transition | one class or one ownership seam | support |
| `module` | guard one module boundary or helper contract | one module family | support |
| `SDK embedded harness` | exercise real SDK registration/runtime composition without Boot shell | embedded runtime + polling/WebSocket worker transports | support |
| `Boot-shell E2E` | prove the real runtime path converges through `xa-mass-dev-app` | HTTP + runtime + transport + persistence projection | core |
| `cross-language sample black-box` | prove public third-party worker references stay runnable and scheduler-correct | external Node/Java processes across polling/realtime adapters | core |
| `concurrency` | prove race-heavy paths converge to an allowed stable state | result/retry/expiry/release/redispatch races | core |
| `perf` | expose hot-path cost, storage pressure, and throughput regressions | engine hot paths and queue-like pressure | core |
| `chaos` | prove the platform degrades and recovers under disruptive conditions | disconnects, delays, restarts, dropped callbacks, queue jitter | scheduled robustness lane |

Current rule:

- core acceptance is `Boot-shell E2E + concurrency + perf`
- `cross-language sample black-box` is also core for external worker compatibility
- `chaos` is scheduled/manual, not a default PR-required lane
- acceptance bias is throughput, HA recovery, and idempotent behavior

## 2. Current Runnable Surfaces

Current runnable acceptance entry points:

- `Boot-shell E2E`: `xa-mass-dev-app`
- `cross-language sample black-box`: `xa-mass-dev-app`
- `concurrency`: `xa-mass-engine`
- `perf`: `xa-mass-testing`
- `SDK embedded harness`: `xa-mass-testing`
- `chaos`: `xa-mass-testing`

Current command entry points:

- see [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md) for copy-paste commands
- see [../xa-mass-engine/README.md](../xa-mass-engine/README.md) for engine race/refill commands
- see [../xa-mass-testing/README.md](../xa-mass-testing/README.md) for perf, SDK harness, and chaos commands
- see [../xa-mass-dev-app/README.md](../xa-mass-dev-app/README.md) for Boot-shell E2E suite map
- use `./scripts/run-external-worker-samples.sh` for the third-party worker validation lane
- use the SDK embedded harness when you need real SDK registration plus transport-aware scheduling pressure without the heavier Boot-shell app context

## 3. CI Placement Rule

| Lane | Placement | Why |
| --- | --- | --- |
| `invariant` / `module` | PR required | cheap, deterministic, high signal for local regressions |
| focused `Boot-shell E2E` | PR required | mainline runtime acceptance must stay green on each change |
| `cross-language sample black-box` | PR required plus nightly scheduled | guards public third-party worker references against in-repo transport shortcuts or hidden assumptions |
| deterministic `concurrency` subset | PR required when the touched path is race-sensitive | catches double-finalize, release races, callback ordering regressions early |
| `perf` smoke | PR optional or non-blocking | useful signal, but CI machine variance should not block every PR |
| heavier `concurrency` matrix | nightly or release | broader race matrices cost more time and are less stable under shared CI noise |
| heavier `perf` baseline/trend checks | nightly or release | throughput and latency thresholds should be compared on more stable cadence |
| `chaos` | nightly, manual, or release | high value for platform safety, but usually too noisy for PR-required gates |

Current workflow files:

From [../.github/workflows/maven.yml](../.github/workflows/maven.yml) and [../.github/workflows/external-worker-samples.yml](../.github/workflows/external-worker-samples.yml):

- `build`: broad `./mvnw -B test`
- `lifecycle-integration`: focused `xa-mass-dev-app` lifecycle integration subset
- `cross-language-blackbox`: explicit external worker sample regression through `./scripts/run-external-worker-samples.sh` on PR/push plus a scheduled daily run

## 4. Change-Type Matrix

| Change type | Minimum acceptance lanes | Also verify |
| --- | --- | --- |
| callback/result handling | `concurrency` + `Boot-shell E2E` | [TRACE_CONTRACT.md](./TRACE_CONTRACT.md) |
| retry / logical-message finality | `concurrency` + `Boot-shell E2E` | state-machine and trace coverage |
| worker release / worker-context release | `concurrency` + `Boot-shell E2E` | engine README + trace coverage |
| assignment refill / batching / gate semantics | `Boot-shell E2E` + targeted `concurrency` | `perf` if the path is hot |
| hot-path storage or counters | `perf` + targeted `concurrency` | one E2E smoke if external behavior could drift |
| lifecycle API/controller behavior | `Boot-shell E2E` | invariant/module tests at the controller-edge seam |
| resilience under disconnect, delay, restart, drop | `chaos` once available | at least one deterministic concurrency surrogate before that |

## 5. Fast Path

When analyzing one specific risk:

1. identify the owning module first
2. use [../xa-mass-engine/README.md](../xa-mass-engine/README.md) for engine races and refill
3. use [../xa-mass-testing/README.md](../xa-mass-testing/README.md) for perf, SDK harness, and chaos
4. use [../xa-mass-dev-app/README.md](../xa-mass-dev-app/README.md) for Boot-shell E2E suite detail
5. only then fan out into broader E2E or trace docs

## 6. Documentation Rule

Keep `doc/` system-level only:

- this file answers cross-module testing questions
- detailed perf, concurrency, chaos, and suite maps belong in the owning module README
- `doc/` should not accumulate module-local testing playbooks
- `E2E_BASELINE.md` and `VERIFIED_RUNBOOK.md` stay project-level because they define release-gate scope and verified runtime behavior
