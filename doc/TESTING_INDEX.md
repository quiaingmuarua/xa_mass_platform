# Testing Index

Last updated: 2026-05-13

Status: current project-level testing index.

This is the default testing entry for both humans and agents.

## 0. Fast Intent

Read this section first if you only need the testing-system intent.

- highest-value proof surface: `engine scheduling correctness`
- representative real wiring proof: `server E2E / external worker parity`
- distributed edge proof: `chaos / perf / black-box`
- keep local kernel tests strong:
  - lifecycle
  - retry
  - expiry
  - release
  - convergence
- downgrade only this test shape:
  - mutate runtime state
  - immediately read compatibility projection
  - treat projection as execution truth
- compatibility projection is bounded residue and report context, not the primary proof surface for runtime correctness

Do not misread the current system:

- this repo did **not** move to `E2E-only`
- `engine` local tests are still first-class PR protection
- `Boot-shell E2E` is representative integrated proof, not the full competition matrix
- `chaos/perf` own distributed edge and runtime pressure, not ordinary lifecycle correctness

Use this file to answer four questions quickly:

1. what the current test layers are
2. what each layer proves and does not prove
3. which tests are the minimum verification for a given change
4. which test shapes are encouraged, downgraded, or being phased out

Use with:

- [TESTING_BASELINE.md](./TESTING_BASELINE.md)
- [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
- [E2E_BASELINE.md](./E2E_BASELINE.md)
- [RESULT_BOUNDARY_BASELINE.md](./RESULT_BOUNDARY_BASELINE.md)
- [TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [../xa-mass-engine/README.md](../xa-mass-engine/README.md)
- [../xa-mass-server/README.md](../xa-mass-server/README.md)
- [../xa-mass-testing/README.md](../xa-mass-testing/README.md)
- [../transport/AGENTS.md](../transport/AGENTS.md)

## 1. Mainline First

Organize test decisions around the current product mainline:

`project -> submitter / worker capability -> task shell -> item append -> engine runtime -> transport delivery -> result ingest -> convergence`

Current testing assumptions:

- `project` is part of the mainline business boundary, not just a metadata page
- `transport` is an explicit subsystem and validation surface, not an engine implementation detail
- the highest-value proof surface is scheduling correctness; host E2E stays the representative integrated wiring proof
- local unit tests are still useful, but new tests should prefer the mainline unless the logic is kernel-critical and easier to prove locally
- local kernel tests remain first-class PR protection for lifecycle/result invariants; what is being downgraded is projection-first proof style, not local kernel testing itself

Fast routing:

- worker/task competition, eligibility, gating, redispatch:
  start with `xa-mass-engine`
- real host/runtime wiring:
  start with `xa-mass-server` E2E
- disconnect, replay, lease expiry, late result, runtime pressure:
  start with `xa-mass-testing`

## 2. Test Layers

### 2.1 Scheduling Correctness

Objects:

- task/worker matching
- schedulable set membership
- worker/context eligibility
- contention and redispatch
- contract-aware convergence

Purpose:

- prove the platform's core business value under real scheduling scenarios
- verify the right worker is selected, the wrong worker is excluded, and the task re-enters competition correctly after delay, retry, or lease expiry

Preferred surfaces:

- `xa-mass-engine` acceptance/concurrency tests as the primary matrix
- representative `xa-mass-server` assignment E2E for real wiring proof
- cross-language external worker black-box tests when adapter/language parity is the risk

Does not prove:

- host shell usability by itself
- throughput or degraded-condition resilience by itself

### 2.2 Kernel Convergence

Objects:

- lifecycle
- retry
- expiry
- finality
- release
- convergence

Purpose:

- prove shared-kernel correctness under concurrency and edge ordering
- protect invariants that sit below the scheduling matrix

Preferred surfaces:

- engine acceptance/concurrency tests
- focused lifecycle/service tests only when they directly protect kernel behavior

Does not prove:

- host HTTP contracts
- transport adapter routing or endpoint behavior

### 2.3 Mainline Boundary

Objects:

- `project`
- `submitter`
- `worker`
- `workerContext`

Purpose:

- prove business boundary, auth boundary, and capability boundary on the real mainline
- verify project/event/submitter/worker constraints are enforced before dispatch

Preferred surfaces:

- Boot-shell E2E
- controller/API contract tests when a host-side surface changes

Does not prove:

- runtime retry/finality correctness by itself
- transport recovery behavior by itself

### 2.4 Transport Boundary

Objects:

- transport runtime
- adapter routing
- result-ingest boundary
- adapter-specific reachability and delivery behavior

Purpose:

- prove transport stays decoupled from engine
- prove adapters route and ingest correctly without redefining kernel semantics

Preferred surfaces:

- transport module tests
- Boot-shell E2E when host/runtime integration is involved

Does not prove:

- task lifecycle convergence on its own
- host resource/auth/read-model behavior on its own

### 2.5 Boot-shell E2E / Black-box

Objects:

- the full chain from project boundary to terminal convergence

Purpose:

- representative integrated proof for the real product mainline
- preferred home for wiring-sensitive behavior that must survive real host/runtime integration
- not the sole or highest-value proof surface for worker/task scheduling correctness

Preferred surfaces:

- `xa-mass-server` integration tests
- cross-language external worker black-box tests

Does not prove:

- hot-path throughput regression
- chaos/recovery robustness beyond the scenario under test

### 2.6 Perf / Chaos / Distributed-readiness

Objects:

- scale behavior
- disconnects
- late replay
- lease expiry
- delayed retry visibility

Purpose:

- prove the mainline still holds under stress and degraded conditions

Preferred surfaces:

- `xa-mass-testing` perf smokes
- `xa-mass-testing` chaos probes

Does not prove:

- ordinary feature correctness by itself
- host controller/resource semantics by itself

## 3. Current CI Truth

Current CI truth comes from workflow files, not from roadmap prose.

PR/push gates:

- `.github/workflows/maven.yml`
  - `reactor-core`
  - `scheduling-core`
  - `server-scheduling-e2e`
  - `lifecycle-integration`
  - `chaos-smokes`
- `.github/workflows/external-worker-samples.yml`
  - `cross-language-blackbox`

Scheduled/manual only:

- `.github/workflows/perf-smokes.yml`
  - `perf-smokes`

Current implications:

- `xa-mass-testing` is compiled on PR via the `reactor-core` compilation gate
- scheduling correctness is an explicit PR gate through engine-first and representative server E2E jobs
- chaos smoke probes are PR-gated
- perf smoke remains scheduled/manual and is not a PR gate
- cross-language black-box remains part of PR validation

## 4. Current Test Asset Map

### Engine Mainline Acceptance

Detailed engine test inventory lives in
[../xa-mass-engine/README.md](../xa-mass-engine/README.md) and the owning suite
classes. This index only records lane placement.

Proves:

- scheduling correctness under contention, retry, lease expiry, and contract-aware convergence
- lifecycle/result invariants around retry, expiry, release, and finality
- contract/intake/runtime owner boundaries without treating compatibility projection as hot-path truth

Does not prove:

- host HTTP/resource contracts
- transport endpoint behavior

Use first when:

- changing lifecycle, retry, expiry, release, or convergence rules
- proving deterministic kernel invariants that would be noisy or slow to localize through E2E/chaos only

Testing rule:

- prefer runtime truth, lease truth, task aggregate truth, and final convergence state as the primary assertion surface
- do not add new lifecycle/result tests that treat compatibility projection as immediate execution truth
- keep compatibility projection assertions only when the residue/read-model contract itself is the subject
- when a scenario fails because the wrong worker was chosen, excluded, or re-chosen, prove it here before duplicating it through more host-shell tests

Secondary explicit residue/audit lanes live under the engine owner README and
suite classes.

These suites remain useful, but they are not the mainline scheduling gate and
must not re-take ownership of runtime correctness.

### Server Mainline E2E

Primary groups:

- assignment and routing
- worker/context availability and reuse
- polling/external-worker wiring
- representative lifecycle/result shell flows

Detailed server E2E inventory lives in
[../xa-mass-server/README.md](../xa-mass-server/README.md) and the owning suite
classes. This index only records lane placement.

Proves:

- the host shell exposes the scheduling mainline correctly
- project/submitter/worker/task flows survive real wiring
- representative assignment, polling, routing, and worker reuse scenarios survive real server + SDK + engine integration
- lifecycle/result convergence gate asserts task aggregate and runtime stats/lease
  truth first; it does not use compatibility message projection as its main
  proof surface

Does not prove:

- the full competition matrix by itself; keep that in engine acceptance first
- long-run throughput
- distributed recovery on its own; use chaos or black-box when disconnect, replay, late result, or takeover behavior is the real risk

Secondary explicit server residue/audit lanes live under the server owner
README and suite classes.

These suites protect bounded compatibility/read-model and diagnostic behavior.
They are useful supporting lanes, but they are not the representative
server-scheduling E2E gate and must not re-own lifecycle or scheduling truth.

### Projection-First Proof Is Downgraded

The following test shape is now downgraded:

- mutate runtime state or ingest a result
- immediately read `TaskMessageProjection` or attempt projection
- treat that compatibility projection as authoritative proof of lifecycle correctness

Rewrite that shape as one of:

- local kernel invariant proof using runtime/lease/finality truth
- bounded residue proof that explicitly awaits async compatibility residue convergence
- integrated proof in Boot-shell E2E, cross-language black-box, or chaos when the real risk is wiring or distributed edge behavior
- disconnect/recovery robustness by itself

Use first when:

- changing project, submitter, worker, task API, mainline authorization, or any host-facing mainline behavior

### Cross-language Black-box

Primary groups:

- `ExternalWorkerParitySuite`
- Java / Node polling
- Java / Node websocket
- Java / Node socket

Proves:

- external worker compatibility
- adapter-specific delivery still lands on the same kernel semantics
- scheduling semantics stay aligned across Java / Node and multiple adapters
- parity tests assert task aggregate, runtime stats, active-lease release, and
  terminal reason first; worker output/read-model checks are only payload parity
  support

Does not prove:

- host-side page/read-model behavior
- internal engine race conditions in isolation

Use first when:

- changing external worker protocol, adapter routing, or result-ingest behavior that crosses the process boundary

### Testing Module

Primary groups:

- perf smoke
- SDK transport harness
- chaos runners

Proves:

- hot-path regression signal
- SDK transport composition behavior
- degraded/recovery behavior around the scheduling mainline
- PR-gated chaos smokes now cover polling all-failed, mixed-result,
  retry-exhausted, polling lease-expiry redispatch, websocket lease-expiry
  redispatch, websocket disconnect/reconnect, and websocket stale late-result
  replay; these runners assert task aggregate, `TaskWorkRuntime` counters,
  active-lease release, final receipts, and `ExecutionEvent` trace transitions
  first

Report-only support:

- chaos reports may include bounded compatibility projection under
  `task.compatibilityProjection` for diagnosis, but those rows are not the
  correctness owner for chaos smoke success/failure
- `run-chaos-smokes.sh` enforces a source guard: PR-gated chaos smoke runners
  may not reference projection helpers or direct task-detail-store projection
  access. Projection reads remain allowed only in explicit report/audit support
  or non-gated runners that have not been promoted.
- `run-perf-smokes.sh` enforces the same ownership shape for perf smokes:
  smoke runners must stay runtime/timing-first and must not use compatibility
  message/attempt projection stats as their proof surface.
- current perf smokes model production risk directly: workload mix reserves an
  interactive lane worker so the smoke measures lane isolation under bulk
  pressure, and interactive retry wakeup starts `RuntimeReadyDispatchPump` so
  delayed retry visibility is consumed from `TaskWorkRuntime`.

Does not prove:

- normal host API contracts
- UI/resource shell semantics

Use first when:

- changing hot runtime paths, disconnect handling, lease expiry, retry visibility, late replay, or transport recovery

## 5. What To Keep, Downgrade, and Phase Out

### Keep

- engine-first scheduling correctness coverage for contention, gating, redispatch, and contract-aware convergence
- Boot-shell E2E
- cross-language black-box
- chaos
- perf
- engine concurrency/acceptance tests around lifecycle, retry, expiry, release, and convergence
- transport tests around adapter routing, delivery, result ingress, and boundary decoupling
- server/controller tests that protect host-side mainline HTTP contracts for `project`, `submitter`, `worker`, and `task`

### Downgrade

- DTO copy/getter/setter tests
- local passthrough tests that only restate an already-proven E2E behavior
- compatibility/read-model tests that provide no extra debugging value

### Phase Out

- server tests that treat `base model` as a stable host-shell API
- server E2E that drives mainline behavior by directly mutating `Task`, `TaskStorage`, or runtime state
- tests organized around historical `message` semantics instead of the current `work` mainline
- local tests that prove only private implementation detail and not a mainline behavior or kernel invariant

## 6. Change Type -> Minimum Verification

| Change type | Minimum verification | Add when needed |
| --- | --- | --- |
| task/worker matching, competition, routing, gating | engine scheduling acceptance/concurrency + representative server scheduling E2E | cross-language black-box when adapter/process parity is at risk |
| `project / submitter / worker / workerContext` boundary | Boot-shell E2E | controller/API contract tests |
| task lifecycle / contract / intake | engine acceptance/concurrency + representative Boot-shell E2E | chaos for degraded edge behavior |
| retry / expiry / finality / result ingest | engine acceptance/concurrency + Boot-shell E2E | chaos for late replay / disconnect / lease expiry |
| `TaskResultRuntime` / stable-final result rows / repair barriers / result read window | runtime contract tests for memory + Redis implementations, plus engine result convergence coverage | Boot-shell `/results` or archive E2E when public result/API shape changes |
| transport runtime / adapter / routing / result ingress | transport module tests + Boot-shell E2E | chaos for recovery behavior |
| host page / filter / shell read model | server integration tests or frontend tests | one Boot-shell smoke if host behavior can drift into mainline |
| hot-path performance / runtime counters | perf smoke + targeted engine acceptance | Boot-shell smoke if external behavior can drift |
| disconnect / delay / late replay / lease expiry | chaos | deterministic engine surrogate when a race needs isolation |

## 7. Rules For Agents

When changing this repo:

1. identify whether the change touches mainline boundary, engine kernel, transport boundary, host shell, or perf/chaos behavior
2. choose the minimum verification from Section 6 before adding any extra tests
3. prefer integration/E2E/edge-case coverage over local unit tests unless the logic is kernel-critical and cheaper to prove locally
4. do not add new server tests that depend on `com.xa.mass.base.model.*` as host-stable API truth
5. do not use direct storage/runtime mutation to manufacture a mainline scenario unless the test is explicitly audit-only or deterministic fault injection

## 8. Read Next

- [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
- [TESTING_BASELINE.md](./TESTING_BASELINE.md)
- [../xa-mass-engine/README.md](../xa-mass-engine/README.md)
- [../xa-mass-server/README.md](../xa-mass-server/README.md)
- [../xa-mass-testing/README.md](../xa-mass-testing/README.md)
