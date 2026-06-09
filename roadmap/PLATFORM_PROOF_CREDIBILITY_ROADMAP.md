# Platform Proof Credibility Roadmap

Status: proposed direction document.

Inventory:

- [PLATFORM_PROOF_CREDIBILITY_INVENTORY.md](./PLATFORM_PROOF_CREDIBILITY_INVENTORY.md)

## Purpose

XA Mass Platform already has strong focused tests, proof registry rows,
trace-observed E2E scenarios, chaos probes, frontend checks, and a packaged
process confidence gate. The remaining problem is proof credibility:

```text
green CI
  -> which critical invariants were actually proven?
  -> which workflows are PR gates versus scheduled evidence?
  -> did a suite execute real testcases?
  -> did the default packaged startup path run?
  -> are trace analyzers and proof rows still resolvable?
  -> which distributed faults are still explicitly not proven?
```

This roadmap turns the current proof ecosystem into an auditable confidence
system without replacing the existing engine/server/SDK/trace proof owners.

## Current Code Observations

- `doc/TESTING_INDEX.md` says the project optimizes for system proof, not
  surface coverage percentage, and that `engine` local tests remain first-class
  PR protection.
- `doc/PROOF_REGISTRY.md` maps critical invariants to primary deterministic
  proof, representative integrated proof, trace proof, and distributed-edge
  proof.
- `xa-mass-testing/src/test/.../ProofRegistryClosureGuardTest.java` checks that
  covered registry rows keep required proof cells populated, named trace
  analyzers resolve, and named proof classes exist.
- Current workflows do not directly run `ProofRegistryClosureGuardTest`; they
  compile `xa-mass-testing` and run chaos/perf/soak scripts with skipped tests.
- `maven.yml` has explicit PR/push jobs for reactor-core,
  scheduling-core, server-scheduling-e2e, lifecycle-integration, and
  chaos-smokes. The core suite jobs already assert that surefire produced
  executed testcases.
- `platform-confidence.yml` now runs a packaged-process confidence smoke for
  explicit `memory-local` and explicit `durable-local`.
- `application.yml` defaults to `durable-local`, while the confidence smoke
  passes `--spring.profiles.active=<profile>` explicitly. That proves explicit
  profile startup, not the no-arg packaged startup contract by itself.
- `perf-smokes.yml` and `soak-smokes.yml` are scheduled/manual evidence lanes,
  not PR gates.
- `redis-runtime.yml` is a focused Redis runtime workflow. It does not prove
  Redis process kill, partition/failover, lease-clock skew, or multi-node
  presence flap.
- `frontend.yml` proves frontend lint/typecheck/test/build. It is not kernel,
  server route, or backend authorization proof.
- `PLATFORM_CONFIDENCE_GATE_ROADMAP.md` is implemented mainline and should be
  consumed rather than duplicated.
- `SERVER_API_CONTRACT_HEALTH_LANE_ROADMAP.md` already owns route/auth/DTO/SDK/
  frontend contract health; this roadmap should not absorb that lane.

## Owner Review

- `doc/TESTING_INDEX.md` owns the high-level testing map, CI truth, and
  change-type verification rules.
- `doc/PROOF_REGISTRY.md` owns critical invariant proof ownership. It is not a
  test runner by itself.
- `xa-mass-engine` owns primary deterministic scheduling and kernel convergence
  proof.
- `xa-mass-server` owns representative Boot-shell E2E, profile/startup/auth
  wiring proof, API route behavior, and server-hosted product proof.
- `xa-mass-testing` owns perf, chaos, soak, packaged-process confidence
  scripts, and proof helper guards.
- `xa-mass-trace` owns canonical trace query/analyzer proof. Raw log greps are
  not trace-observed proof.
- `.github/workflows/*` are the current CI truth and must be reflected in
  testing docs.
- `frontend` owns control-console quality checks and adapter consumption. It
  must not become backend/API/kernel truth.

## Boundary Decision

Proof credibility is a coordination layer over existing proof owners:

```text
Proof registry
  -> declares critical invariant proof ownership

Workflow gates
  -> execute selected primary/integrated/edge proof

Proof manifest
  -> records what ran, testcase counts, analyzers, scenarios, profiles,
     artifacts, and known non-proof boundaries

Testing index
  -> explains current CI truth and minimum verification
```

The proof credibility lane must not create a second scheduling matrix, a second
API contract lane, or a replacement E2E suite. It keeps proof claims honest and
reviewable.

## Hard Rules

1. Do not replace engine scheduling/core convergence tests with broader E2E.
2. Do not add tests only to raise coverage. Every new gate must name the risk
   class or invariant it proves.
3. Do not claim explicit-profile confidence smoke proves no-arg startup unless
   no-arg packaged startup actually ran.
4. Do not claim Redis infra-fault proof for process kill, partition/failover,
   lease-clock skew, or multi-node presence flap until a deterministic harness
   or seam exists.
5. Do not use frontend tests as backend route/auth/kernel proof.
6. Do not make proof manifests source of runtime truth. They are CI evidence.
7. Do not let support or `secondary-proof` tests leak back into mainline suites.
8. Do not duplicate existing `PLATFORM_CONFIDENCE_GATE_ROADMAP.md` or
   `SERVER_API_CONTRACT_HEALTH_LANE_ROADMAP.md` responsibilities.
9. Any workflow or testing-doc change must keep `doc/TESTING_INDEX.md`,
   `doc/PROOF_REGISTRY.md`, and the actual workflow files consistent.
10. Every new CI gate must fail if no testcase/scenario actually executed.

## Non-Goals

- No coverage percentage mandate.
- No all-in-one mega E2E.
- No new test framework.
- No production runtime, scheduling, storage, transport, API, SDK, or frontend
  behavior change in the planning slices.
- No Redis cluster/failover implementation in this roadmap unless PPC-4
  explicitly splits a successor infra-fault harness roadmap.
- No broad frontend product QA roadmap.
- No route-family contract health duplication; ACH remains the owner.

## Do Not Start With

Do not start by adding another broad E2E suite or coverage target. The current
weakness is not lack of tests; it is that proof claims, workflow truth, startup
contracts, and artifact interpretation are not yet all mechanically tied
together.

Do not start by faking Redis infra faults through worker state, local flags, or
test-only runtime shortcuts. The repo already records those faults as
not-current-proof until the harness/seam exists.

## PPC-0 Current Proof Inventory And CI Truth Repair

Goal: make the proof map match current workflows and establish the first
proof-credibility baseline.

Scope:

- Update `PLATFORM_PROOF_CREDIBILITY_INVENTORY.md` from current code if any
  workflow or suite has changed since this roadmap was written.
- Update `doc/TESTING_INDEX.md` current CI truth so it lists:
  - Maven core/scheduling/server/lifecycle/chaos gates,
  - platform confidence gate,
  - external worker samples,
  - Redis runtime workflow,
  - perf smoke scheduled/manual lane,
  - soak smoke scheduled/manual lane,
  - frontend workflow and its boundary.
- Keep `doc/PROOF_REGISTRY.md` unchanged unless an invariant proof owner is
  actually changing.
- Decide workflow placement for registry guard tests.
- Decide proof-manifest format and artifact location.

Acceptance:

- Testing index no longer omits active workflows that affect confidence.
- Testing index distinguishes PR gates, path-filtered gates, scheduled/manual
  evidence, and frontend-only checks.
- Inventory records every active workflow and its current proof claim.
- Registry guard workflow placement is decided.
- No runtime or product behavior changes are included in this slice.

Verification:

```powershell
rg -n "Current CI Truth|platform-confidence|redis-runtime|soak-smokes|frontend|ProofRegistryClosureGuardTest" doc roadmap .github xa-mass-testing -g "*.md" -g "*.yml" -g "*.java"
git diff --check
```

## PPC-1 Registry Closure As A PR Gate

Goal: make the proof registry mechanically trustworthy on every PR.

Scope:

- Add a CI step or job that runs at least:
  - `ProofRegistryClosureGuardTest`
  - `WorkerFaultScenarioIndexTest`
  - `WorkerFaultReportMetadataTest`
- Keep the command focused so it is not blocked by long-running perf/soak
  lanes.
- Preserve the current rule that `xa-mass-testing` script runners can compile
  without executing all module tests.
- Ensure the job fails if zero testcases execute.
- If the registry guard finds a missing analyzer or class, fix the registry or
  proof owner instead of deleting the guard.

Acceptance:

- PR CI fails when `doc/PROOF_REGISTRY.md` names a missing proof class.
- PR CI fails when a registry-named trace analyzer is not registered.
- PR CI fails when covered registry rows lose required proof cells.
- PR CI reports executed testcase count.
- The registry guard stays a proof-map guard, not a broad chaos/perf runner.

Verification:

```powershell
./mvnw.cmd -q -pl xa-mass-testing -am "-Dtest=ProofRegistryClosureGuardTest,WorkerFaultScenarioIndexTest,WorkerFaultReportMetadataTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.github/scripts/assert-surefire-executed-tests.sh xa-mass-testing/target/surefire-reports
```

## PPC-2 No-Arg Packaged Startup Contract Proof

Goal: prove the human/default packaged startup contract separately from
explicit-profile confidence smoke.

Scope:

- Add a focused no-arg packaged startup proof, either:
  - a new mode in `run-platform-confidence-smoke.sh`, or
  - a small dedicated startup smoke script.
- Start the packaged server jar without `--spring.profiles.active`.
- Observe that it resolves to `spring.profiles.default=durable-local`.
- Preserve the durable-local startup order:
  - seed/import,
  - operator credential readiness,
  - runtime start.
- Verify readiness after `CommandLineRunner` guards, not only Tomcat listener
  start.
- Keep this separate from the full task/worker confidence flow unless the
  script can clearly report the owner category.

Acceptance:

- A clean no-arg packaged server starts to healthy readiness when expected seed
  inputs are present.
- Failure output distinguishes Tomcat started from full application readiness.
- Missing operator credentials fail with the readiness-guard category and
  explicit message.
- The no-arg proof is documented as a startup contract proof, not scheduling
  or external-worker proof.
- `platform-confidence.yml` or a separate workflow runs this proof on PR, or
  the roadmap records why it is staged scheduled/manual first.

Verification:

```powershell
./mvnw.cmd -q -pl xa-mass-server,tools/xa-mass-admin-cli,integrations/xa-mass-scenario-launcher -am -DskipTests package
rg -n "spring.profiles.default|durable-local|OperatorAuthReadinessGuard|Started XaMassServerApplication|actuator/health" xa-mass-server xa-mass-testing .github -g "*.java" -g "*.yml" -g "*.sh" -g "*.md"
```

## PPC-3 Proof Manifest And Artifact Summary

Goal: make a green run explain what it proved.

Scope:

- Add a small proof-summary artifact generator or script that can run in CI
  after selected gates.
- The first manifest should include:
  - workflow/job name,
  - suite or scenario id,
  - testcase count where available,
  - critical invariant ids when mapped,
  - trace analyzer ids when mapped,
  - profile/runtime backend,
  - artifact paths,
  - known non-proof boundaries.
- Keep this as CI evidence only. Do not make it a runtime API or database
  truth.
- Prefer consuming surefire XML, chaos/perf/soak report JSON, and known
  workflow metadata over parsing raw logs.

Acceptance:

- Scheduling-core job output names `EngineSchedulingCoreSuite` and
  `EngineKernelConvergenceSuite` plus testcase counts.
- Server E2E output names representative suite and testcase counts.
- Chaos smoke output names the three PR scenarios and their analyzer/proof
  relation where stable.
- Platform confidence output names profile, auth mode, and result path.
- The manifest explicitly says perf/soak are scheduled/manual evidence when
  they did not run in PR.
- The manifest does not overclaim Redis infra-fault proof.

Verification:

```powershell
rg -n "TEST-.*xml|surefire-reports|chaos-reports|perf-reports|soak-reports|platform-confidence|summary.json" .github xa-mass-testing scripts -g "*.sh" -g "*.yml" -g "*.java"
```

## PPC-4 Distributed Fault Boundary Decision

Goal: turn the current Redis/transport not-current-proof list into an explicit
successor decision instead of leaving it as scattered warnings.

Scope:

- Re-read the archived worker-fault infra-fault decision and current
  `doc/TESTING_INDEX.md` warnings.
- Decide whether to create a new active infra-fault harness roadmap for:
  - Redis process kill,
  - Redis partition/failover,
  - lease-clock skew or non-monotonic clock behavior,
  - multi-node presence flap.
- If split, the successor roadmap must define:
  - deterministic local/CI harness,
  - runtime clock seam if needed,
  - Redis fault injection mechanism,
  - multi-node transport/presence owner boundary,
  - trace proof and artifact shape,
  - initial scheduled/manual status before PR promotion.
- If not split, keep the not-current-proof boundary explicit in testing docs
  and proof manifest.

Acceptance:

- There is no active doc that claims the four infra-fault classes are covered.
- If a successor roadmap is created, this roadmap links to it and stops
  owning implementation details.
- If no successor is created, testing docs and proof manifest clearly retain
  the not-current-proof list.
- No fake chaos row is added through worker-pack state or local flags.

Verification:

```powershell
rg -n "Redis process kill|partition/failover|lease-clock skew|multi-node presence flap|infra-fault|not current proof" doc roadmap xa-mass-testing -g "*.md"
```

## PPC-5 Perf And Soak Trend Evidence

Goal: convert scheduled perf/soak runs from ad hoc artifacts into release
confidence evidence.

Scope:

- Define the first stable perf/soak evidence set:
  - workload mix perf smoke,
  - interactive retry wakeup smoke,
  - polling scheduling fast soak,
  - any scenario ids already stable in runner ledgers.
- Decide which values are thresholds versus trend-only:
  - assignment latency,
  - retry wakeup latency,
  - success/failure counts,
  - active lease drain,
  - trace dropped count,
  - analyzer pass/fail.
- Decide artifact retention and comparison target:
  - previous green scheduled run,
  - checked-in baseline,
  - release note evidence only.
- Keep perf/soak scheduled/manual unless the first thresholds are stable enough
  for PR gating.

Acceptance:

- Perf and soak reports have stable scenario ids.
- Reports include enough metadata to compare environment, backend, profile, and
  scenario.
- Release confidence can point to latest green scheduled perf/soak evidence.
- PR CI does not become flaky because of unstable perf thresholds.
- Perf/soak runners continue using runtime/timing-first proof, not review rows.

Verification:

```powershell
xa-mass-testing/scripts/run-perf-smokes.sh
xa-mass-testing/scripts/run-polling-scheduling-fast-soak.sh
rg -n "scenarioId|proof|runtimeInvariants|droppedCount|latency|threshold|perf-reports|soak-reports" xa-mass-testing -g "*.java" -g "*.sh" -g "*.md"
```

## PPC-6 Residue Scan And Archive Readiness

Goal: close roadmap/document drift after the proof credibility lane lands.

Scope:

- Scan for stale CI truth in docs.
- Scan for proof claims that no longer match workflows.
- Scan for archived worker-fault decision links that point to removed active
  paths.
- Scan for support or `secondary-proof` tests leaking into mainline suites.
- Update owner docs that describe how agents should choose verification.
- Archive this roadmap only after all completion criteria are satisfied and
  current facts move into owner docs.

Acceptance:

- `doc/TESTING_INDEX.md` and workflows agree.
- `doc/PROOF_REGISTRY.md` guard runs in CI.
- No active doc claims unavailable infra-fault proof.
- No current roadmap duplicates PCG or ACH responsibilities.
- Completed facts are moved to owner docs before archive.

Verification:

```powershell
rg -n "Current CI Truth|platform-confidence|redis-runtime|soak-smokes|frontend|ProofRegistryClosureGuardTest|not current proof|secondary-proof" doc roadmap .github xa-mass-engine xa-mass-server xa-mass-testing -g "*.md" -g "*.yml" -g "*.java"
git diff --check
```

## Verification Matrix

| Area | Minimum proof |
| --- | --- |
| Proof registry closure | `ProofRegistryClosureGuardTest` plus executed-test assertion |
| Scheduling primary proof | `EngineSchedulingCoreSuite` |
| Kernel convergence primary proof | `EngineKernelConvergenceSuite` |
| Server scheduling E2E | `ServerSchedulingE2eSuite` |
| Server lifecycle/result E2E | `ServerLifecycleResultConvergenceSuite` |
| External worker parity | `ExternalWorkerParitySuite` / external worker samples workflow |
| Packaged process confidence | `run-platform-confidence-smoke.sh --profile memory-local` and `--profile durable-local` |
| No-arg startup | PPC-2 startup proof command or workflow |
| Chaos smoke | `run-chaos-smokes.sh` |
| Redis runtime parity | `redis-runtime.yml` focused module/server smoke |
| Perf/soak evidence | scheduled `perf-smokes.yml` and `soak-smokes.yml` reports |
| Frontend quality | `pnpm lint`, `pnpm typecheck`, `pnpm test:run`, `pnpm build` |

## Risks

| Risk | Why It Matters | Mitigation |
| --- | --- | --- |
| Proof roadmap becomes a test-count project | Adds noise without increasing trust | Every slice names the invariant or risk class it proves. |
| E2E replaces engine proof | Slower, weaker localization and hidden matrix gaps | Keep engine suites as primary proof in registry and CI. |
| CI docs drift again | Agents and maintainers misread green runs | PPC-0 updates testing index and PPC-6 residue scans it. |
| No-arg startup remains unproven | Human local startup can fail while explicit profile CI passes | PPC-2 makes default startup its own contract. |
| Registry guard is local-only | Registry can name stale classes/analyzers silently | PPC-1 makes it a PR gate. |
| Proof manifest overclaims | Green summary becomes misleading | Manifest must include known non-proof boundaries. |
| Redis infra faults are faked | False confidence around distributed recovery | PPC-4 requires deterministic harness/seam or explicit non-proof status. |
| Perf thresholds become flaky PR gates | CI noise reduces trust | Keep perf/soak scheduled/manual until thresholds stabilize. |
| Frontend checks are misclassified | UI green is treated as backend contract proof | Testing index and manifest keep frontend boundary explicit. |

## Completion Criteria

This roadmap can be marked complete only when:

1. `doc/TESTING_INDEX.md` accurately lists current PR, path-filtered,
   scheduled/manual, and frontend workflows.
2. Registry closure guard tests run in CI and fail on stale proof rows,
   missing proof classes, or missing trace analyzers.
3. No-arg packaged startup is separately proven or explicitly documented as a
   known gap with owner and successor.
4. CI emits or uploads a proof summary that maps major gates to suites,
   testcase counts, profiles, scenarios, analyzers, and artifacts.
5. Platform confidence evidence distinguishes explicit profile proofs from
   no-arg startup proof.
6. Redis infra-fault non-proof boundaries are either split into an active
   successor roadmap or remain clearly marked as not-current-proof in docs and
   proof summaries.
7. Perf/soak reports have stable scenario ids and release-facing interpretation.
8. Frontend workflow success is documented as frontend quality/adapter proof
   only, not server/kernel proof.
9. Residue scan finds no active doc claiming proof that the workflows do not
   actually run.
10. Current facts are moved into owner docs before this roadmap is archived.
