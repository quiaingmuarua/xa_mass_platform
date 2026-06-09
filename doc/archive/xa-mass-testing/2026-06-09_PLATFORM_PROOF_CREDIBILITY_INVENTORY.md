# Platform Proof Credibility Inventory

Status: first baseline implemented and archived current-code inventory for
`2026-06-09_PLATFORM_PROOF_CREDIBILITY_ROADMAP.md`; CI observation and richer
manifest normalization remain follow-up work.

## Scope

This inventory records the current proof, CI, active-profile API/auth,
startup, trace, chaos, perf, and frontend verification surfaces that affect
whether a green run can be trusted.

It does not redefine kernel behavior. Runtime truth remains owned by engine,
runtime, worker-runtime, transport, server, and trace owners according to their
existing contracts. This inventory is about proof coordination and drift
control.

## Proof Surfaces

| Surface | Current Owner | Current Role | Classification | Credibility Target |
| --- | --- | --- | --- | --- |
| `doc/TESTING_INDEX.md` | global testing docs | Explains test layers, CI truth, and change-type verification. | proof map | Must match current workflow files and minimum verification rules. |
| `doc/PROOF_REGISTRY.md` | global proof registry | Maps critical invariants to primary, integrated, trace, and distributed-edge proof. | invariant registry | Must remain executable and tied to real classes/analyzers. |
| `ProofRegistryClosureGuardTest` | `xa-mass-testing` | Checks covered registry rows, named proof classes, and trace analyzers; wired into `maven.yml / proof-credibility`. | registry guard | Must stay PR-gated and fail when registry proof references drift. |
| `EngineSchedulingCoreSuite` | `xa-mass-engine` | Deterministic scheduling correctness gate. | primary proof | Must stay the first proof surface for scheduling competition and selection. |
| `EngineKernelConvergenceSuite` | `xa-mass-engine` | Deterministic lifecycle/result convergence gate. | primary proof | Must stay runtime/result-first and avoid projection-first proof. |
| `ServerSchedulingE2eSuite` | `xa-mass-server` | Representative host/runtime scheduling E2E. | integrated proof | Must prove real server wiring without replacing engine matrix coverage. |
| `ServerLifecycleResultConvergenceSuite` | `xa-mass-server` | Representative lifecycle/result convergence E2E. | integrated proof | Must prove real host/runtime result paths and trace-observed scenarios. |
| `ExternalWorkerParitySuite` | `xa-mass-server` | External worker public contract parity. | integrated / black-box proof | Must keep SDK/external worker behavior aligned across adapters. |
| `ServerStartupProfileSuite` | `xa-mass-server` | Groups `ServerMemoryLocalProfileContextTest` and `ServerDurableLocalProfileContextTest`; wired into `platform-confidence.yml / server-default-startup`. | profile assembly support proof | Durable-local context proof remains temp-SQLite/dynamic-property support, while the default-path process proof is owned by the no-arg startup smoke. |
| `TraceScenarioRegistry` and `xa-mass-trace` analyzers | `xa-mass-trace` / test owners | Canonical trace-observed proof lookup. | trace proof | Registry-named analyzers must resolve and read canonical trace, not raw logs. |
| `run-chaos-smokes.sh` | `xa-mass-testing` | PR-gated distributed-edge recovery smoke. | chaos proof | Must stay runtime/aggregate/trace-first and avoid projection-derived pass/fail. |
| `run-perf-smokes.sh` | `xa-mass-testing` | Scheduled/manual perf smoke bundle; defaults workload mix to a stable scenario id. | perf proof | Release interpretation lives in `proof/perf-soak-release-evidence.json`: runner invariants are hard threshold signals, latency/throughput are trend-only. |
| `run-polling-scheduling-fast-soak.sh` | `xa-mass-testing` | Scheduled/manual soak lane; defaults to `polling-soak-noisy-mixed-result`. | soak proof | Release interpretation lives in `proof/perf-soak-release-evidence.json` and proof summary artifacts. |
| `run-platform-confidence-smoke.sh` | `xa-mass-testing` | Packaged server + admin CLI + Java SDK process proof. | product confidence gate | Must prove every supported active profile through real HTTP APIs, session operator auth, API-key task/worker auth, fixture-header-off behavior, and representative credential-family failure reasons in `summary.json`. |
| `run-server-default-startup-smoke.sh` | `xa-mass-testing` | Starts the packaged server jar with no application arguments from an isolated working directory, proves default durable-local health/login, and restarts the same default SQLite file. | operator startup proof | Must stay separate from API/auth confidence and report default profile, default profile log observation, SQLite path, restart count, Redis namespace mode, liveness, login, and log-failure scan. |
| `.github/workflows/maven.yml` | CI | Core reactor, engine, server E2E, lifecycle, chaos smoke gates. | PR gate | Must keep suite execution assertions and add registry guard coverage. |
| `.github/workflows/platform-confidence.yml` | CI | Packaged process confidence matrix for `memory-local` and `durable-local`. | PR gate | Must be reflected in testing docs and artifact summaries as real API/auth proof, not internal-interface proof. |
| `.github/workflows/external-worker-samples.yml` | CI | Cross-language external worker black-box validation. | PR/scheduled gate | Must stay tied to external worker parity proof ownership. |
| `.github/workflows/redis-runtime.yml` | CI | Redis runtime focused tests and server Redis smoke. | conditional/scheduled gate | Must be described as runtime backend parity, not full infra-fault proof. |
| `.github/workflows/perf-smokes.yml` | CI | Scheduled/manual perf smoke bundle. | scheduled proof | Should produce comparable reports and release evidence. |
| `.github/workflows/soak-smokes.yml` | CI | Scheduled polling scheduling fast soak. | scheduled proof | Should feed a stable proof summary, not only artifacts. |
| `.github/workflows/frontend.yml` | CI | Frontend lint, typecheck, tests, and build. | frontend quality gate | Must not be claimed as kernel/server truth. |

## Current CI Gate Classification

| Workflow / Job | Trigger | Current Proof | Current Gap |
| --- | --- | --- | --- |
| `maven.yml / reactor-core` | PR/push | Reactor tests through `integrations/xa-mass-worker-pack -am test`; compiles `xa-mass-testing`. | Broad compile/test gate; proof credibility guards run in the focused `proof-credibility` job. |
| `maven.yml / scheduling-core` | PR/push | `EngineSchedulingCoreSuite` and `EngineKernelConvergenceSuite`, with surefire executed-test assertions and proof-summary upload. | Strong primary proof; richer invariant/analyzer manifest normalization remains follow-up work. |
| `maven.yml / proof-credibility` | PR/push | `ProofRegistryClosureGuardTest`, worker-fault guard tests, `PlatformConfidenceProfileMatrixGuardTest`, and `ProofSummaryWorkflowGuardTest`, with surefire executed-test assertion. | Focused proof-map, matrix, and artifact-shape guard; not a runtime smoke lane. |
| `maven.yml / server-scheduling-e2e` | PR/push | `ServerSchedulingE2eSuite`, with executed-test assertion. | Representative proof only; not full competition matrix. |
| `maven.yml / lifecycle-integration` | PR/push | `ServerLifecycleResultConvergenceSuite`, with executed-test assertion and proof-summary upload. | Strong lifecycle integrated proof; richer invariant/analyzer manifest normalization remains follow-up work. |
| `maven.yml / chaos-smokes` | PR/push | Three fast distributed-edge recovery scenarios. | Does not cover Redis process kill, partition/failover, lease-clock skew, or multi-node presence flap. |
| `platform-confidence.yml / packaged-process-confidence` | PR/push/manual | Packaged process smoke for explicit `memory-local` and explicit `durable-local`, using external admin/task/worker processes, session auth, API-key routes, and negative credential checks. | Active-profile API/auth proof; no-arg default startup remains separate. |
| `platform-confidence.yml / server-default-startup` | PR/push/manual | Runs `ServerStartupProfileSuite`, then starts the packaged server jar with no app args, logs in as the seeded operator, and restarts the same SQLite file. | Operator startup/restart proof; not scheduling, task/worker, or full permission-matrix proof. |
| `external-worker-samples.yml` | PR/push/manual/scheduled | Cross-language black-box external worker sample validation. | Needs to stay linked to external parity registry rows. |
| `redis-runtime.yml` | path-filtered PR/push/scheduled | Redis runtime module tests and one server Redis runtime smoke. | Conditional proof; not a broad Redis infra-fault matrix. |
| `perf-smokes.yml` | scheduled/manual | Perf smoke bundle with stable workload-mix scenario id and proof-summary release interpretation. | Not a PR gate; latency/throughput remain trend-only until calibrated thresholds are promoted. |
| `soak-smokes.yml` | scheduled/manual | Polling scheduling fast soak with stable noisy mixed-result scenario id and proof-summary release interpretation. | Not a PR gate; release evidence is latest green scheduled/manual artifact. |
| `frontend.yml` | frontend path PR/push | Frontend lint/typecheck/test/build. | Frontend success must not be used to claim server/kernel proof. |

## Remaining Gaps After Current Slice

1. The new packaged confidence and default-startup jobs still need a full CI
   runtime pass with a reachable Redis service before the roadmap can claim the
   current slice as observed green in CI.
2. `ServerDurableLocalProfileContextTest` remains profile-assembly support with
   a temp SQLite path and dynamic properties. Default-path proof is owned by
   `run-server-default-startup-smoke.sh`, not by the context test.
3. `write-proof-summary.mjs` creates a first JSON proof summary from scoped
   surefire XML and lane-local reports in CI, but richer invariant-id,
   trace-analyzer, scenario, and artifact normalization remains follow-up work.
4. Redis runtime owner restart/reconnect is covered as scheduled/manual chaos
   support, but process kill, partition/failover, lease-clock skew, and
   multi-node presence flap remain explicitly not-current-proof.
5. Perf and soak now have first-slice release interpretation, but calibrated
   latency/throughput baselines remain future work before any PR promotion.
6. Frontend verification is healthy for UI quality but can be misread as API or
   kernel proof unless the testing index and contract health lane keep the
   boundary explicit.

## Related Roadmaps And Records

| Record | Relation |
| --- | --- |
| `xa-mass-testing/README.md` platform confidence smoke | Implemented packaged-process confidence gate. This roadmap consumes that owner proof and adds active-profile API/auth proof interpretation, fail-closed checks, and proof-summary planning. |
| `xa-mass-server/src/main/resources/application.yml` and `application-durable-local.yml` | Current default startup contract: default profile is `durable-local`; durable-local defaults to local SQLite, Redis runtime/transport, session auth, and operator credential seed. |
| `SERVER_API_CONTRACT_HEALTH_LANE_ROADMAP.md` | Route/auth/DTO/SDK/frontend contract health lane. This roadmap should not duplicate route-family proof. |
| `xa-mass-server/README.md` API observability | API failure and endpoint metrics diagnostics used by proof reports. |
| `PLATFORM_SCHEDULING_PLANE_STABILIZATION_AND_PROOF_ROADMAP.md` | Scheduling-plane proof strengthening; this roadmap should reference its proof status rather than re-own scheduling semantics. |
| `doc/archive/xa-mass-testing/2026-06-05_WORKER_FAULT_MATRIX_INFRA_FAULT_DECISION.md` | Archived decision that infra-fault rows need deterministic harness/seam work before they can be claimed as proof. |
| `doc/FRONTEND_BACKEND_CONTRACT.md` | Frontend/backend proof boundary and adapter update rules. |

## PPC-0 Decisions Closed

1. Registry guard tests run in `maven.yml / proof-credibility`, not inside the
   broad reactor job or the chaos-smokes job.
2. The first proof summary is JSON only at
   `xa-mass-testing/target/proof-summary/summary.json`; markdown is deferred
   until there is a concrete reviewer need.
3. The supported active-profile allowlist source is
   `xa-mass-testing/proof/platform-confidence-profiles.txt`.
4. `PlatformConfidenceProfileMatrixGuardTest` compares the allowlist,
   `platform-confidence.yml` matrix, and `application-*.yml` resources. The
   confidence script reads the allowlist instead of preserving a hard-coded
   profile gate.
5. New `application-*.yml` profiles require explicit allowlist review; they do
   not silently become supported platform-confidence profiles.
6. Redis runtime workflow remains path-filtered plus scheduled/manual. Broad
   Redis infra-fault proof is deferred to PPC-4.
7. Platform confidence owns three representative negative auth checks:
   unauthenticated operator route, invalid task API key, and invalid worker API
   key. ACH retains the full route-permission matrix.
8. Fixture-header-off proof uses the admin CLI `auth config` path. The CLI
   prints full `/api/v1/auth/config` fields and the smoke asserts
   `operatorHeaderSupported=false`.
9. PPC-2 `summary.json` minimum schema is:
   `profile`, `authMode`, `operatorHeaderSupported`,
   `fixtureHeaderDisabled`, `sessionCookieSupported`, `adminRouteFamilies`,
   `sdkRouteFamilies`, `credentialChecks` with `httpStatus` / `code` /
   `failureReason`, and `confidenceOverlay`.
10. Default startup/restart proof is a PR gate in
    `platform-confidence.yml / server-default-startup`.
11. Default startup smoke runs from an isolated working directory with no
    application arguments and keeps the default relative SQLite path.
12. The current smoke uses the durable-local default Redis namespace and labels
    `redisNamespaceMode=default`. If CI isolation is introduced later, the
    output must change to `ci-isolated`.
13. Same-SQLite durable restart belongs in the PR smoke, not only a scheduled
    follow-up lane.

Deferred decisions:

- PPC-4 decision: do not create a successor infra-fault roadmap in this slice;
  keep Redis process kill, partition/failover, lease-clock skew, and multi-node
  presence flap explicitly not-current-proof until a deterministic harness and
  owner seam are proposed.
- PPC-5 decision: first release-facing perf/soak evidence is JSON-defined in
  `xa-mass-testing/proof/perf-soak-release-evidence.json`; runner invariants
  are hard threshold signals, while latency/throughput are trend-only and the
  lanes remain scheduled/manual.

## Hard Boundary

- Proof-system credibility coordinates what is proven, where it is proven, and
  how proof output is interpreted.
- It must not create new runtime behavior, new scheduling policy, new API
  routes, frontend-owned API truth, or fake chaos rows.
- A green proof lane must name the invariant or risk class it proves. If the
  risk class is not named, the lane is support coverage only.
- No-arg startup is not a separate credibility target unless the repo declares
  it as a runtime contract distinct from selecting an already proven active
  profile.
- Default startup/restart proof is operator-startup confidence. It must not be
  reported as task/worker scheduling proof or full API permission-matrix proof.
