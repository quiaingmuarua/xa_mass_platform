# Testing Baseline

Last updated: 2026-04-25

This is the top-level map of the testing system.

Use it first when you need to answer any of these questions quickly:

- which test layer owns acceptance for this change
- which lane should run in PR CI vs nightly vs release
- where a new agent should start when analyzing one specific runtime risk

Use with:

- [./VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
- [./INTEGRATION_TESTS.md](./INTEGRATION_TESTS.md)
- [./E2E_BASELINE.md](./E2E_BASELINE.md)
- [./TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [./testing/TOPIC_INDEX.md](./testing/TOPIC_INDEX.md)

## 1. Test Taxonomy

Keep the layers distinct:

| Layer | Primary job | Typical scope | Acceptance weight |
| --- | --- | --- | --- |
| `invariant` | prove correctness of one local rule or state transition | one class or one ownership seam | support |
| `module` | guard one module boundary or helper contract | one module family | support |
| `Boot-shell E2E` | prove the real runtime path converges through `xa-mass-dev-app` | HTTP + runtime + transport + persistence projection | core |
| `concurrency` | prove race-heavy paths converge to an allowed stable state | result/retry/expiry/release/redispatch races | core |
| `perf` | expose hot-path cost, storage pressure, and throughput regressions | engine hot paths and queue-like pressure | core |
| `chaos` | prove the platform degrades and recovers under disruptive conditions | disconnects, delays, restarts, dropped callbacks, queue jitter | planned core robustness lane |

Working rule:

- `E2E`, `concurrency`, and `perf` are the current runnable core acceptance stack
- `chaos` is important, but should stay a scheduled or release-oriented lane until the suite is stable and deterministic enough to trust
- `invariant` and `module` tests matter, but they are not sufficient evidence that the platform is ready

## 2. Current Runnable Surfaces

Current runnable acceptance entry points:

- `Boot-shell E2E`: `xa-mass-dev-app`
- `concurrency`: `xa-mass-engine`
- `perf`: `xa-mass-testing`

Current command entry points:

- see [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md) for copy-paste commands
- see [testing/TOPIC_INDEX.md](./testing/TOPIC_INDEX.md) for point-specific commands
- converge new cross-cutting harnesses into `xa-mass-testing`; keep engine-local deterministic race tests in `xa-mass-engine` until they no longer need engine-internal proximity

Current non-runnable or thinner lane:

- `chaos` does not yet have a first-class mainline suite in this repo snapshot

## 3. Recommended CI Placement

Do not collapse every lane into one required gate.

| Lane | Recommended placement | Why |
| --- | --- | --- |
| `invariant` / `module` | PR required | cheap, deterministic, high signal for local regressions |
| focused `Boot-shell E2E` | PR required | mainline runtime acceptance must stay green on each change |
| deterministic `concurrency` subset | PR required when the touched path is race-sensitive | catches double-finalize, release races, callback ordering regressions early |
| `perf` smoke | PR optional or non-blocking | useful signal, but CI machine variance should not block every PR |
| heavier `concurrency` matrix | nightly or release | broader race matrices cost more time and are less stable under shared CI noise |
| heavier `perf` baseline/trend checks | nightly or release | throughput and latency thresholds should be compared on more stable cadence |
| `chaos` | nightly, manual, or release | high value for platform safety, but usually too noisy for PR-required gates |

PR rule:

- keep the required gate short and deterministic
- keep heavier trend and resilience checks visible, but do not force every pull request to pay the full cost

## 4. Current Workflow Snapshot

Current CI wiring is still simpler than the recommended target.

From [../.github/workflows/maven.yml](../.github/workflows/maven.yml):

- `build`: broad `./mvnw -B test`
- `lifecycle-integration`: focused `xa-mass-dev-app` lifecycle integration subset

This means:

- the repo already treats `E2E`-style runtime checks as CI-significant
- `concurrency`, `perf`, and `chaos` lane placement is still only partially expressed in workflow structure
- document the intended lane placement now, then evolve CI wiring later without losing the test-system shape

## 5. Change-Type Matrix

Use this matrix when deciding what must move with a change.

| Change type | Minimum acceptance lanes | Also verify |
| --- | --- | --- |
| callback/result handling | `concurrency` + `Boot-shell E2E` | [TRACE_CONTRACT.md](./TRACE_CONTRACT.md) |
| retry / logical-message finality | `concurrency` + `Boot-shell E2E` | state-machine and trace coverage |
| worker release / worker-context release | `concurrency` + `Boot-shell E2E` | topic card + trace coverage |
| assignment refill / batching / gate semantics | `Boot-shell E2E` + targeted `concurrency` | `perf` if the path is hot |
| hot-path storage or counters | `perf` + targeted `concurrency` | one E2E smoke if external behavior could drift |
| lifecycle API/controller behavior | `Boot-shell E2E` | invariant/module tests at the controller-edge seam |
| resilience under disconnect, delay, restart, drop | `chaos` once available | at least one deterministic concurrency surrogate before that |

## 6. Agent Fast Path

When a new agent needs to analyze one specific point:

1. read [./testing/TOPIC_INDEX.md](./testing/TOPIC_INDEX.md)
2. open the matching topic card under `doc/testing/topics/`
3. run the lane-specific command from [./VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
4. only then fan out into broader E2E or trace docs

The goal is to start from the problem, not from a pile of general docs.

## 7. Documentation Rule

Keep testing docs layered:

- this file answers system-level questions
- `TOPIC_INDEX.md` answers navigation questions
- topic cards answer point-specific analysis questions
- `E2E_BASELINE.md`, `INTEGRATION_TESTS.md`, and `VERIFIED_RUNBOOK.md` stay as the detailed fact sources for their own lanes
