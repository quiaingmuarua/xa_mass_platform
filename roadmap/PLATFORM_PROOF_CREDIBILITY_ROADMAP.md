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
  -> did every supported active profile run through real HTTP APIs and auth?
  -> did any confidence lane bypass permissions or call internal services?
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
  passes `--spring.profiles.active=<profile>` explicitly. The default no-arg
  path is therefore only profile-selection convenience; the confidence proof
  should focus on every supported active profile.
- The default durable-local path uses `./data/xa-mass-sqlite/xa_mass.db`,
  Redis runtime/transport, session operator auth, and
  `classpath:control-plane-seed/operator-credentials.json`.
- `ServerDurableLocalProfileContextTest` and
  `ServerMemoryLocalProfileContextTest` exist, but current workflows do not
  run them as a named startup-profile suite. The durable-local context test
  uses a temp SQLite path and dynamic properties, so it is not proof of the
  default `./data/...` packaged-process startup path.
- No current PR lane starts the packaged server jar with no application
  arguments from an isolated working directory, observes the default
  durable-local profile/path, restarts against the same SQLite file, and checks
  seed/operator readiness at process level.
- `run-platform-confidence-smoke.sh` starts the packaged server jar, requires
  session operator auth, disables the local fixture header, runs the admin CLI,
  worker launcher, task launcher, operator task command, and task-result
  verifier as external processes.
- `tools/xa-mass-admin-cli` uses Java `HttpClient` against `/api/v1/auth/*`,
  `/api/v1/control-plane/*`, `/api/v1/api-keys`, and task command routes. Its
  production dependencies do not include server or engine modules.
- `integrations/xa-mass-scenario-launcher` depends on `xa-mass-java-sdk`, not
  server or engine internals. The SDK uses `MassHttpClient` with API-key
  headers against `/api/v1/*` and `/worker-api/v1/*`.
- Existing architecture guards prevent the scenario launcher from importing
  platform internals and prevent Java integrations from hard-coding public
  platform routes outside the SDK boundary.
- `perf-smokes.yml` and `soak-smokes.yml` are scheduled/manual evidence lanes,
  not PR gates.
- `redis-runtime.yml` is a focused Redis runtime workflow. It does not prove
  Redis process kill, partition/failover, lease-clock skew, or multi-node
  presence flap.
- `frontend.yml` proves frontend lint/typecheck/test/build. It is not kernel,
  server route, or backend authorization proof.
- The packaged-process confidence gate is implemented and documented by
  `xa-mass-testing/README.md`, `xa-mass-testing/VERIFIED_RUNBOOK.md`, and
  `.github/workflows/platform-confidence.yml`; consume those owner records
  rather than duplicating the archived completion roadmap.
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
     auth modes, API/client boundaries, artifacts, and known non-proof
     boundaries

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
3. Do not spend proof budget on no-arg startup unless it represents a distinct
   supported runtime contract. Default no-arg profile selection is not a
   stronger proof than proving the active profile it selects.
4. Every active-profile confidence claim must prove real HTTP routes and auth
   boundaries; internal Java service calls, direct DB writes, or fixture
   headers are not platform confidence proof.
5. Default startup and durable restart proof is operator-startup confidence.
   It must not replace PPC-2 API/auth confidence or engine/server E2E proof.
6. Do not claim Redis infra-fault proof for process kill, partition/failover,
   lease-clock skew, or multi-node presence flap until a deterministic harness
   or seam exists.
7. Do not use frontend tests as backend route/auth/kernel proof.
8. Do not make proof manifests source of runtime truth. They are CI evidence.
9. Do not let support or `secondary-proof` tests leak back into mainline suites.
10. Do not duplicate existing packaged-process confidence-smoke or
   `SERVER_API_CONTRACT_HEALTH_LANE_ROADMAP.md` responsibilities.
11. Any workflow or testing-doc change must keep `doc/TESTING_INDEX.md`,
   `doc/PROOF_REGISTRY.md`, and the actual workflow files consistent.
12. Every new CI gate must fail if no testcase/scenario actually executed.

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

Do not start by replacing the active-profile API/auth proof with a no-arg
startup gate. The current proof-credibility weakness is not only that
`java -jar` omits a profile argument; it is that proof claims, workflow truth,
active-profile API/auth boundaries, default durable startup, and artifact
interpretation are not yet all mechanically tied together.

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

## PPC-2 Active Profile API/Auth Confidence Matrix

Goal: prove that every supported active profile runs the packaged server and
external clients through real HTTP APIs and permission checks, without internal
service, DB, or fixture-header shortcuts.

Scope:

- Inventory supported active profiles from `xa-mass-server` profile resources
  and workflow inputs. Current active profiles are `memory-local` and
  `durable-local`.
- Keep profile selection explicit in CI: run `run-platform-confidence-smoke.sh`
  once for each supported active profile.
- For each profile, start the packaged server jar and run external admin,
  worker, task, operator-command, and result-verifier processes.
- Require `mass.auth.operator.mode=session` and
  `mass.auth.operator.allow-local-fixture-header=false` in the confidence lane.
- Require admin bootstrap and operator commands to use `/api/v1/auth/*`,
  `/api/v1/control-plane/*`, `/api/v1/api-keys`, and task command HTTP routes.
- Require task and worker flows to use the external Java SDK through
  `/api/v1/*` and `/worker-api/v1/*` with API-key headers.
- Add or preserve architecture guards that fail if admin/scenario production
  code imports server, engine, runtime, storage, or worker-pack internals.
- Add one representative fail-closed check per credential family:
  unauthenticated operator route fails, invalid task API key fails, and invalid
  worker API key fails. Full route-permission matrix remains owned by ACH.
- Document no-arg startup as default profile selection only. Do not create a
  no-arg-only proof lane unless the repo adds a distinct no-arg runtime
  contract beyond selecting `durable-local`.

Acceptance:

- The confidence workflow matrix covers all supported active profiles.
- Each profile produces evidence for server jar startup, auth mode, local
  fixture-header disabled status, admin HTTP routes, SDK task routes, SDK
  worker routes, operator task command, and task-result visibility.
- The confidence lane fails if operator auth is not session-backed.
- The confidence lane fails if local fixture-header auth is enabled.
- The confidence lane fails if external launcher/admin modules gain production
  dependencies or imports on server/engine/runtime internals.
- Representative invalid credential checks fail closed and are reported as
  auth/API-boundary evidence, not full ACH replacement.
- No PPC-2 acceptance criterion depends on no-arg packaged startup proof.

Verification:

```powershell
rg --files xa-mass-server/src/main/resources | rg "application-.*\.yml"
xa-mass-testing/scripts/run-platform-confidence-smoke.sh --profile memory-local
xa-mass-testing/scripts/run-platform-confidence-smoke.sh --profile durable-local
./mvnw.cmd -q -pl tools/xa-mass-admin-cli,integrations/xa-mass-scenario-launcher,sdk/xa-mass-java-sdk -am "-Dtest=AdminEnvServiceTest,ScenarioLauncherArchitectureGuardTest,JavaExternalSdkArchitectureGuardTest" test
rg -n "allow-local-fixture-header|authMode|/api/v1|/worker-api/v1|X-Mass-Api-Key|com\\.xa\\.mass\\.(server|engine|runtime|storage)" xa-mass-testing tools/xa-mass-admin-cli integrations/xa-mass-scenario-launcher sdk/xa-mass-java-sdk -g "*.java" -g "*.sh" -g "*.xml"
```

## PPC-2B Server Default Startup And Durable Restart Smoke

Goal: prove the human/operator default server startup contract separately from
active-profile API/auth confidence.

Scope:

- Add a focused server default startup smoke, either as a dedicated script under
  `xa-mass-testing/scripts/` or as a clearly named mode that is not confused
  with `run-platform-confidence-smoke.sh`.
- Add a named `ServerStartupProfileSuite` that includes
  `ServerMemoryLocalProfileContextTest` and
  `ServerDurableLocalProfileContextTest` as profile-assembly support proof.
- Package `xa-mass-server` and start the packaged jar with no application
  arguments from an isolated working directory.
- Do not pass `--spring.profiles.active`; observe that the default profile is
  `durable-local`.
- Let durable-local use its default local SQLite path relative to the isolated
  working directory: `./data/xa-mass-sqlite/xa_mass.db`.
- Use the Redis service on localhost and the durable-local default Redis
  namespace unless the script explicitly documents CI isolation for Redis keys.
- Wait for `/actuator/health`, then keep observing briefly so a post-Tomcat
  startup failure cannot be missed.
- Assert the server process remains alive and the log does not contain
  `Application run failed`.
- Run admin operator login through the admin CLI after health so the smoke
  proves seed/import -> operator credential readiness -> runtime start.
- Stop the server, start it a second time from the same working directory and
  same SQLite file, then repeat health, process-alive, log, and operator-login
  checks.
- Keep task/worker scenario launcher coverage out of this first slice unless it
  is split into a separate human-config smoke. PPC-2 owns API/auth confidence.

Acceptance:

- PR CI or a documented scheduled lane starts the packaged server jar without
  `spring.profiles.active`.
- `ServerStartupProfileSuite` runs the memory-local and durable-local profile
  context tests as a named suite and reports executed testcases.
- The smoke proves default `durable-local` startup reaches health and stays
  alive after health.
- The smoke fails when the log contains `Application run failed`.
- Operator login succeeds after the default seed/import path, proving session
  credentials are usable at process level.
- A second startup against the same SQLite file succeeds, proving durable-local
  seed idempotence, schema sidecar compatibility, and operator credential
  persistence for the current default local path.
- The smoke output explicitly classifies this as default startup/restart proof,
  not scheduling, task/worker, or full route-permission proof.

Verification:

```powershell
./mvnw.cmd -q -pl xa-mass-server -am "-Dtest=ServerStartupProfileSuite" "-Dsurefire.failIfNoSpecifiedTests=false" test
./mvnw.cmd -q -pl xa-mass-server,tools/xa-mass-admin-cli -am -DskipTests package
rg -n "spring.profiles.default|default: durable-local|./data/xa-mass-sqlite/xa_mass.db|operator-credentials-location|Application run failed|auth login" xa-mass-server xa-mass-testing tools/xa-mass-admin-cli -g "*.yml" -g "*.java" -g "*.sh"
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
  - auth mode and credential family,
  - public route family or SDK client used,
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
- Platform confidence output names profile, auth mode, fixture-header status,
  admin CLI route family, SDK route family, credential family, and result path.
- Default startup output names default profile, working directory, SQLite path,
  restart count, health result, operator-login result, and log-failure scan.
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
| Active-profile API/auth confidence | PPC-2 confidence matrix and architecture guards |
| Default startup / durable restart | PPC-2B packaged jar no-arg smoke with same SQLite file restart |
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
| Confidence lane bypasses auth/API | Green run can hide permission or route breakage | PPC-2 requires session auth, fixture-header-off proof, external clients, and invalid-credential checks. |
| Default startup fails after Tomcat start | Human local startup can print early started logs and still fail during readiness guards | PPC-2B waits for health, observes process liveness, scans logs, and runs operator login. |
| Local SQLite restart breaks seed/auth readiness | A clean CI DB can hide default durable-local persistence failures | PPC-2B restarts the same working-directory SQLite file and proves operator credentials remain usable. |
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
3. Every supported active profile is covered by packaged-process confidence
   proof through real HTTP APIs and auth boundaries.
4. Default packaged startup and same-SQLite durable-local restart are either
   PR-gated or explicitly recorded as scheduled evidence with owner and
   promotion criteria.
5. CI emits or uploads a proof summary that maps major gates to suites,
   testcase counts, profiles, auth modes, route/client families, scenarios,
   analyzers, and artifacts.
6. Platform confidence evidence distinguishes real API/auth proof from support
   lanes that use lower-level harnesses.
7. Redis infra-fault non-proof boundaries are either split into an active
   successor roadmap or remain clearly marked as not-current-proof in docs and
   proof summaries.
8. Perf/soak reports have stable scenario ids and release-facing interpretation.
9. Frontend workflow success is documented as frontend quality/adapter proof
   only, not server/kernel proof.
10. Residue scan finds no active doc claiming proof that the workflows do not
   actually run.
11. Current facts are moved into owner docs before this roadmap is archived.
