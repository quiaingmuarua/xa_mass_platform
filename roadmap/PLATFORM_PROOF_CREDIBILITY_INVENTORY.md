# Platform Proof Credibility Inventory

Status: initial current-code inventory for
`PLATFORM_PROOF_CREDIBILITY_ROADMAP.md`.

## Scope

This inventory records the current proof, CI, startup, trace, chaos, perf, and
frontend verification surfaces that affect whether a green run can be trusted.

It does not redefine kernel behavior. Runtime truth remains owned by engine,
runtime, worker-runtime, transport, server, and trace owners according to their
existing contracts. This inventory is about proof coordination and drift
control.

## Proof Surfaces

| Surface | Current Owner | Current Role | Classification | Credibility Target |
| --- | --- | --- | --- | --- |
| `doc/TESTING_INDEX.md` | global testing docs | Explains test layers, CI truth, and change-type verification. | proof map | Must match current workflow files and minimum verification rules. |
| `doc/PROOF_REGISTRY.md` | global proof registry | Maps critical invariants to primary, integrated, trace, and distributed-edge proof. | invariant registry | Must remain executable and tied to real classes/analyzers. |
| `ProofRegistryClosureGuardTest` | `xa-mass-testing` | Checks covered registry rows, named proof classes, and trace analyzers. | registry guard | Should be a PR gate, not only local support. |
| `EngineSchedulingCoreSuite` | `xa-mass-engine` | Deterministic scheduling correctness gate. | primary proof | Must stay the first proof surface for scheduling competition and selection. |
| `EngineKernelConvergenceSuite` | `xa-mass-engine` | Deterministic lifecycle/result convergence gate. | primary proof | Must stay runtime/result-first and avoid projection-first proof. |
| `ServerSchedulingE2eSuite` | `xa-mass-server` | Representative host/runtime scheduling E2E. | integrated proof | Must prove real server wiring without replacing engine matrix coverage. |
| `ServerLifecycleResultConvergenceSuite` | `xa-mass-server` | Representative lifecycle/result convergence E2E. | integrated proof | Must prove real host/runtime result paths and trace-observed scenarios. |
| `ExternalWorkerParitySuite` | `xa-mass-server` | External worker public contract parity. | integrated / black-box proof | Must keep SDK/external worker behavior aligned across adapters. |
| `TraceScenarioRegistry` and `xa-mass-trace` analyzers | `xa-mass-trace` / test owners | Canonical trace-observed proof lookup. | trace proof | Registry-named analyzers must resolve and read canonical trace, not raw logs. |
| `run-chaos-smokes.sh` | `xa-mass-testing` | PR-gated distributed-edge recovery smoke. | chaos proof | Must stay runtime/aggregate/trace-first and avoid projection-derived pass/fail. |
| `run-perf-smokes.sh` | `xa-mass-testing` | Scheduled/manual perf smoke bundle. | perf proof | Should become trend evidence with stable scenario ids and thresholds. |
| `run-polling-scheduling-fast-soak.sh` | `xa-mass-testing` | Scheduled/manual soak lane. | soak proof | Should produce stable proof reports and release confidence evidence. |
| `run-platform-confidence-smoke.sh` | `xa-mass-testing` | Packaged server + admin CLI + Java SDK process proof. | product confidence gate | Must distinguish explicit-profile proof from no-arg startup proof. |
| `.github/workflows/maven.yml` | CI | Core reactor, engine, server E2E, lifecycle, chaos smoke gates. | PR gate | Must keep suite execution assertions and add registry guard coverage. |
| `.github/workflows/platform-confidence.yml` | CI | Packaged process confidence matrix for `memory-local` and `durable-local`. | PR gate | Must be reflected in testing docs and artifact summaries. |
| `.github/workflows/external-worker-samples.yml` | CI | Cross-language external worker black-box validation. | PR/scheduled gate | Must stay tied to external worker parity proof ownership. |
| `.github/workflows/redis-runtime.yml` | CI | Redis runtime focused tests and server Redis smoke. | conditional/scheduled gate | Must be described as runtime backend parity, not full infra-fault proof. |
| `.github/workflows/perf-smokes.yml` | CI | Scheduled/manual perf smoke bundle. | scheduled proof | Should produce comparable reports and release evidence. |
| `.github/workflows/soak-smokes.yml` | CI | Scheduled polling scheduling fast soak. | scheduled proof | Should feed a stable proof summary, not only artifacts. |
| `.github/workflows/frontend.yml` | CI | Frontend lint, typecheck, tests, and build. | frontend quality gate | Must not be claimed as kernel/server truth. |

## Current CI Gate Classification

| Workflow / Job | Trigger | Current Proof | Current Gap |
| --- | --- | --- | --- |
| `maven.yml / reactor-core` | PR/push | Reactor tests through `integrations/xa-mass-worker-pack -am test`; compiles `xa-mass-testing`. | `xa-mass-testing` guard tests are not run as tests here. |
| `maven.yml / scheduling-core` | PR/push | `EngineSchedulingCoreSuite` and `EngineKernelConvergenceSuite`, with surefire executed-test assertions. | Strong primary proof; no proof-manifest output yet. |
| `maven.yml / server-scheduling-e2e` | PR/push | `ServerSchedulingE2eSuite`, with executed-test assertion. | Representative proof only; not full competition matrix. |
| `maven.yml / lifecycle-integration` | PR/push | `ServerLifecycleResultConvergenceSuite`, with executed-test assertion. | Strong lifecycle integrated proof; no unified invariant summary. |
| `maven.yml / chaos-smokes` | PR/push | Three fast distributed-edge recovery scenarios. | Does not cover Redis process kill, partition/failover, lease-clock skew, or multi-node presence flap. |
| `platform-confidence.yml` | PR/push/manual | Packaged process smoke for explicit `memory-local` and explicit `durable-local`. | Does not by itself prove no-arg `java -jar` default startup. |
| `external-worker-samples.yml` | PR/push/manual/scheduled | Cross-language black-box external worker sample validation. | Needs to stay linked to external parity registry rows. |
| `redis-runtime.yml` | path-filtered PR/push/scheduled | Redis runtime module tests and one server Redis runtime smoke. | Conditional proof; not a broad Redis infra-fault matrix. |
| `perf-smokes.yml` | scheduled/manual | Perf smoke bundle. | Not a PR gate; needs trend/baseline evidence for release trust. |
| `soak-smokes.yml` | scheduled/manual | Polling scheduling fast soak. | Not a PR gate; needs stable report interpretation. |
| `frontend.yml` | frontend path PR/push | Frontend lint/typecheck/test/build. | Frontend success must not be used to claim server/kernel proof. |

## Current Gaps

1. `ProofRegistryClosureGuardTest` is useful and passes locally, but current
   workflows do not directly execute it as a PR gate.
2. `doc/TESTING_INDEX.md` has drifted behind workflow reality: it omits the
   platform confidence gate, Redis runtime workflow, frontend workflow, and
   soak workflow from current CI truth.
3. The platform confidence gate proves explicit `memory-local` and
   `durable-local` profiles, but no current lane separately proves no-arg
   packaged startup with `spring.profiles.default=durable-local`.
4. CI uploads raw test/log artifacts, but there is no unified proof manifest
   that maps invariant ids to executed suite, testcase count, trace analyzer,
   chaos scenario, and artifact path.
5. Redis runtime owner restart/reconnect is covered as scheduled/manual chaos
   support, but process kill, partition/failover, lease-clock skew, and
   multi-node presence flap remain explicitly not-current-proof.
6. Perf and soak reports are available, but they are not yet treated as
   comparable trend evidence with release-facing thresholds.
7. Frontend verification is healthy for UI quality but can be misread as API or
   kernel proof unless the testing index and contract health lane keep the
   boundary explicit.

## Related Roadmaps And Records

| Record | Relation |
| --- | --- |
| `PLATFORM_CONFIDENCE_GATE_ROADMAP.md` | Implemented packaged-process confidence gate. This roadmap consumes it and adds no-arg/default-startup and proof-summary planning. |
| `SERVER_API_CONTRACT_HEALTH_LANE_ROADMAP.md` | Route/auth/DTO/SDK/frontend contract health lane. This roadmap should not duplicate route-family proof. |
| `SERVER_API_OBSERVABILITY_ROADMAP.md` | API failure and endpoint metrics diagnostics used by proof reports. |
| `PLATFORM_SCHEDULING_PLANE_STABILIZATION_AND_PROOF_ROADMAP.md` | Scheduling-plane proof strengthening; this roadmap should reference its proof status rather than re-own scheduling semantics. |
| `doc/archive/xa-mass-testing/2026-06-05_WORKER_FAULT_MATRIX_INFRA_FAULT_DECISION.md` | Archived decision that infra-fault rows need deterministic harness/seam work before they can be claimed as proof. |
| `doc/FRONTEND_BACKEND_CONTRACT.md` | Frontend/backend proof boundary and adapter update rules. |

## Decisions To Close In PPC-0

1. Exact workflow placement for registry guard tests:
   - inside `maven.yml / reactor-core`, or
   - a new `proof-registry` job, or
   - inside `chaos-smokes` before scenario execution.
2. Exact proof-manifest format:
   - JSON only,
   - markdown summary,
   - both.
3. Whether no-arg packaged startup proof is:
   - a new mode inside `run-platform-confidence-smoke.sh`,
   - a tiny dedicated startup smoke script,
   - or a Spring Boot shell test plus separate packaged-process command.
4. Whether Redis runtime workflow should always run on PR or remain
   path-filtered plus scheduled.
5. Release-facing rule for perf/soak:
   - latest scheduled green required,
   - threshold comparison required,
   - advisory artifact only.
6. Whether infra-fault harness planning belongs in this roadmap or should split
   into a separate active roadmap after PPC-4.

## Hard Boundary

- Proof-system credibility coordinates what is proven, where it is proven, and
  how proof output is interpreted.
- It must not create new runtime behavior, new scheduling policy, new API
  routes, frontend-owned API truth, or fake chaos rows.
- A green proof lane must name the invariant or risk class it proves. If the
  risk class is not named, the lane is support coverage only.
