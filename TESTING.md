# XA Mass Proof Selection

Status: current repository proof-lane registry entrypoint.

Tests are organized by mechanism claim, not by coverage percentage. A test is
valuable when it is the cheapest credible proof of one named invariant or
boundary. Repeating the same success path at another scale does not create a
new claim.

[Proof Registry](doc/testing/proof-registry.md) owns claims, nonclaims and
Primary Owners. Each linked Integration README owns its complete world,
workload, mutation sequence and oracle. This file owns selection, commands,
prerequisites and CI routing.

For project orientation, follow the [root reading path](README.md#reading-path)
from Runtime summary through the behavior model to the affected Owner and caller.
Then choose proof for the changed claim. Matching, Execution and Convergence
span existing owners and proof lanes.
For a mechanism walkthrough, use the
[mainline code/proof pointers](doc/kernel/scheduling-overview.md#production-and-proof-pointers)
before selecting a lane. The pointers identify representative assertions, not
fresh run evidence. Docs Contract checks entrypoints, links and selected retired
terms; semantic agreement still requires comparing prose with its Owner/caller
and assertions.

Deployment configuration proof lives in `server_boot_jvm`: the default, Lab,
AgentForge and preview classpath configurations, explicit overrides and deployment
overlays. `server_jvm` tests and OpenAPI export use independent test resources;
only named real-infrastructure tests enable Redis and Pacers. Archive verification
checks the four host configurations, absence of application configuration in
nested platform/Scenario libraries, and absence of test configuration. Moving
configuration ownership retains the original proof-path selections.

Business workloads retain their scenario assertions while platform regressions
belong to the owning proof. Select the dedicated SMS Preview workflow for SMS
composition, console and source/fresh-ZIP delivery changes; its claims and limits
are registered under [SMS Preview](doc/testing/proof-registry.md#sms_reception_preview).
The [SMS Owner](scenarios/sms-reception-jvm/README.md#检查与验收) owns the complete
workload and observation bounds. Launcher and archive tests belong to Distribution.
The larger product workload remains explicit/manual, outside ordinary Proof Gate.

```powershell
python -m pip install -r distribution/server/requirements-preview.txt
python scenarios/sms-reception-jvm/run_acceptance.py --build --scenario functional
python scenarios/sms-reception-jvm/run_acceptance.py --scenario lifecycle
python scenarios/sms-reception-jvm/run_acceptance.py --scenario concurrency
```

[App Checks](scenarios/app-checks-jvm/README.md#装配与证明) uses the existing finite
scenario lane for composition, allocation projection, assignment-window and
source/fresh-ZIP execution changes. Its Owner defines fixtures and observation
bounds; [Product Coexistence](doc/testing/proof-registry.md#product_coexistence)
registers the claims without promoting projections to reliable counters or quotas.

[Scenario Coexistence](integrations/scenario-coexistence/README.md) runs functional,
Pool-selection and lifecycle scenarios against source artifacts; fresh ZIP repeats
functional and Pool selection. Its independent CI steps retain later outcomes
after a failure while every required failure still fails the job. The larger dual
workload is explicit/manual:

```powershell
python integrations/scenario-coexistence/run_proof.py --build --scenario functional
python integrations/scenario-coexistence/run_proof.py --scenario pool-selection --port 18560
python integrations/scenario-coexistence/run_proof.py --scenario lifecycle
python integrations/scenario-coexistence/run_proof.py --scenario load-1k
```

Install `distribution/server/requirements-preview.txt`; use `--root` with a fresh
extracted Preview to exercise its packaged launcher. The dedicated
`.github/workflows/product-coexistence.yml` also accepts `scenario=load-1k` through
workflow_dispatch. CI uploads only safe summary/manifest evidence.

## Proof Model

Every mechanism claim has one **Primary Proof**. A repeated check elsewhere is
a prerequisite or Boundary Witness, not a second owner of that invariant.

| Proof | Contract |
| --- | --- |
| Owner Test | Local algorithm, legal transition, strict contract or concurrency fence |
| Boundary Proof | Encoding and behavior across adjacent owners or processes |
| [Worker Correctness](integrations/worker-correctness/README.md) | Exact identity, route, live Properties without Prepare, extension, Result and restart closure |
| [Worker Dynamic Matching](integrations/worker-dynamic-matching/README.md) | Loaded Matching query execution follows live facts, with actual executor and Result witnesses |
| [Worker Convergence Health](integrations/worker-convergence-health/README.md) | Named witness convergence after established state and process faults |
| [Worker Loaded Capacity + Recovery Stability](integrations/worker-loaded-recovery/README.md) | Sustained work, repeated Server recovery and resource bounds |
| [Android Worker](integrations/android-worker-proof/README.md) | Real Android lifecycle and fixed multi-process isolation |

World size is a fixture, not a proof level. Do not multiply topology, Group or
Adapter cardinality, mutation and workload dimensions into a Cartesian product.
Runtime Boundary owns protocol combinations; named convergence witnesses do not
imply all-offered success. A larger world does not replace correctness or create
a throughput, latency or resource claim. Shared Inventory materialization does
not transfer scenario or oracle ownership.

For the affected mechanism, follow its Owner and the registered proof boundary:

| Affected mechanism | Contract and proof entry |
| --- | --- |
| Prepare, Binding and Project descriptors | [Server](server_jvm/README.md#workergroup-and-worker-preparation), [Redis Owner](doc/testing/proof-registry.md#redis_owner), [Runtime Boundary](doc/testing/proof-registry.md#runtime_boundary) |
| Worker network evidence, candidate generations and execution fences | [Worker Score Owner](kernel_jvm/doc/score/worker-score-band-scheduling.md), [Redis Owner](doc/testing/proof-registry.md#redis_owner) |
| Matching admission, Properties, indexes and Pool resources | [Matching Owner](worker_matching_jvm/README.md#cost-failure-and-proof), [JVM Contracts](doc/testing/proof-registry.md#jvm_contracts), [Redis Owner](doc/testing/proof-registry.md#redis_owner) |
| Item outcomes and independent Result content | [Item Score](kernel_jvm/doc/score/task-item-score-band-scheduling.md), [Result Owner](kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md), [Runtime Boundary](doc/testing/proof-registry.md#runtime_boundary) |
| Allocation observations and assignment windows | [Observation selection](#allocation-observations-and-app-checks-projection) |

Proof Registry preserves claims and nonclaims; these links are selection aids,
not another definition of transitions, storage budgets or evidence guarantees.

## Selection Decision

Project topology and startup changes select Server/Boot contracts; persistent
Project descriptors and directory changes additionally select Redis Owner.
Query admission and Pool changes select Matching contracts, with Redis Owner
for Facts/index atomicity and Runtime Boundary for actual Worker traversal.
The [registered claims](doc/testing/proof-registry.md) identify the exact boundary;
Integration Owners retain their fixed workload and observation limits.

Use the lowest-cost proof that owns the changed claim:

1. A local algorithm, state transition, validation rule or race changes: run
   the focused Owner test.
2. A DTO, codec, HTTP, Redis or adjacent-owner contract changes: run the
   corresponding Boundary Proof in addition to focused Owner tests.
3. Worker identity, Prepare, long-lived delivery, extensions, shared Lab/SMS Worker Simulator or
   Worker-facing Server behavior changes: run Worker Correctness.
4. Kernel/Pacer scheduling, serviceability, Runtime projections or Worker fault
   convergence changes: run Worker Convergence Health. TaskItem finality remains
   an Owner and Runtime Boundary claim; Convergence may be selected only as a
   downstream witness.
5. Worker/Platform Properties, Matching, Candidate invalidation, assignment or
   per-replica Handler assembly changes: run Worker Dynamic Matching. It owns
   loaded execution witnesses; Redis Owner still owns exact seal/transfer
   ordering and the separate facts/Score commit boundary.
6. Java Worker connection resource ownership changes: run focused Java Worker
   tests; Worker Loaded Capacity + Recovery Stability remains nightly/manual
   unless its loaded recovery or resource-stability claim must be re-established.
7. Documentation-only changes run Docs Contract. They do not select runtime
   proofs.
8. Call-performance Harness or runner changes run their deterministic JVM/Python
   tests. Full offered-load and latency measurement belongs to the independent
   nightly/manual performance lane. A production optimization re-establishes its
   Owner claim and the selected existing lanes, then uses the lane's per-case
   A/B (`baseline_ref` with `lane_case`) for comparison; do not duplicate
   throughput or Worker-size tiers across lanes. `--diagnostics jfr` is manual
   attribution with private recordings and whitelist export; A/B keeps it off.
   The same module owns the local capacity experiment and CI saturation settings.
   Use its [world/configuration](integrations/worker-call-performance/README.md#world-and-configuration)
   and [local experiment](integrations/worker-call-performance/README.md#local-10k-capacity-experiment)
   contracts instead of copying resource values into correctness-lane selection.

An edit limited to API documentation metadata (for example `@Schema` bounds)
and its generated OpenAPI snapshot does not change DTO shape or runtime admission.
For local verification, export the snapshot and run the focused Server OpenAPI
contract/validation tests plus Docs Contract. Path-based CI selection remains
conservative: Java paths can still select runtime lanes, and the Proof Gate still
requires every selected lane. The local check set is not a claim that those CI
lanes ran, or permission to bypass them. Runtime validation/DTO behavior changes
retain the ordinary Owner and Boundary proof requirements above.

Inspect selection for a branch without running a proof:

```powershell
python .github/scripts/explain_proof_selection.py --base origin/main
```

The checked representative-path contract is:

```powershell
python .github/scripts/check_proof_selection.py
```

The checker uses existing tracked and non-ignored untracked files, excluding
uncommitted deletions so package moves fail locally before CI. Kernel Worker
registration, Server Endpoint configuration and Prepare select both Java Worker
Correctness and Android Worker Proof after the Binding ownership move.

## Assignment ceiling proof

Assignment-ceiling or round-budget changes select focused Pacer tests and the
[Redis Owner composition](doc/testing/proof-registry.md#redis_owner) for the full
qualification-to-publication and failure-settlement path. The
[Pacer assembly](kernel_pacer_jvm/doc/application-assembly.md) owns the ceiling
and resource budgets. Observation changes also select the Server Properties
handler contracts. Performance configuration experiments remain separate from
these correctness claims and add no heavy ordinary CI lane.

## Lane Index

| Lane | Primary command | External dependency |
| --- | --- | --- |
| JVM Contracts | Explicit non-Android Gradle module `build` tasks | None |
| Redis Owner | `.\gradlew.bat :server_jvm:redisOwnerIntegrationTest` | Redis 7 |
| Runtime Boundary | `.\gradlew.bat :server_jvm:runtimeBoundaryIntegrationTest` | Redis 7 |
| Worker Correctness | `python integrations/worker-correctness/run_worker_correctness.py --redis-url redis://127.0.0.1:6379/15` | Redis, Server, Worker Simulator |
| Worker Dynamic Matching | `python integrations/worker-dynamic-matching/run_worker_dynamic_matching.py --redis-url redis://127.0.0.1:6379/15` | Redis, Server, Worker Simulator |
| Worker Convergence Health | `python integrations/worker-convergence-health/run_worker_convergence_health.py --scenario all --redis-url redis://127.0.0.1:6379/15` | Redis, Server, Worker Simulator |
| Worker Loaded Capacity + Recovery Stability | `python integrations/worker-loaded-recovery/run_worker_loaded_recovery.py --prepared-workers 15000 --retained-workers 10000 --minimum-initial-converged 14800 --minimum-retained-converged 9900 --workload-items-per-task 5000 --redis-url redis://127.0.0.1:6379/15` | Linux, Redis, Java 21 |
| [Worker Call Performance](integrations/worker-call-performance/README.md) | Nightly: parallel `rate-500`, `rate-1000`, `rate-2000` and `saturation` jobs with three repetitions, merged, recorded on `perf-lane-data` and compared with the last seven records. Manual: the same with optional JFR diagnostics, or per-case A/B with `baseline_ref` and `lane_case` | Ubuntu 24.04, 4 logical CPUs, Docker Redis 7.4.10, Java 21 |
| Android Host | Android unit/library builds plus `:integrations:android-worker-proof:test` | Robolectric, MockWebServer, JDK HttpServer |
| Android APK Assembly | Debug plus three fixed Lab APK variants in Proof CI | Android SDK |
| Android Worker Proof | `Android Worker Proof` in Proof CI | Redis, KVM API 33 Emulator |
| Frontend | `pnpm lint`, `typecheck`, `test`, `build`, `build:demo` | Node, pnpm |
| Runtime Distribution | Distribution integration tests with `-PxaMassVersion=0.5.0` | Redis, Java, Android SDK, Node |
| Scenario Coexistence | `python integrations/scenario-coexistence/run_proof.py --build --scenario functional`, then `--scenario pool-selection`, `--scenario lifecycle`; fresh ZIP functional and pool-selection | Redis 7, Java 21, Python, Node for build |
| Docs Contract | `python .github/scripts/check_docs.py` | None |

The exact JVM module build list and Android assembly commands are maintained in
[Proof CI](.github/workflows/proof-ci.yml), alongside their environment setup.
The JVM lane also installs Worker Simulator and runs
`python -m unittest discover -s worker_simulator_jvm/src/test/python -p 'test_*.py'`.
This invokes its actual installed entry with one configuration from a different
working directory and verifies generation and inventory reuse without a Server.
It does not replace the independently observed Worker Correctness or product proofs.

For documentation checks, run both the checker tests and the repository scan:

```powershell
python .github/scripts/test_check_docs.py
python .github/scripts/check_docs.py
python .github/scripts/test_check_proof_selection.py
python .github/scripts/check_proof_selection.py
```

Building the unified console or either UI archive requires Node 22 and Corepack.
The Server JAR no longer builds or embeds SMS frontend assets. Composition proof
builds the console explicitly and tests both profile states against that output.

Worker one-shot runners support Python 3.11 or newer. Install their small
shared dependency set once before running them locally:

```powershell
python -m pip install -r .github/scripts/requirements.txt
```

Redis protocol, authentication, TLS and URL handling belong to `redis-py`.
Proof runners must not maintain a private RESP client. Each high-level lane
retains one orchestration entrypoint because its process topology and failure
sequence are part of that lane's evidence.

The local Worker Convergence `--scenario all` entry remains the complete
one-shot command. Proof CI runs `state` and `task-fault` as two independent
matrix jobs, each with its own Redis service, scope, evidence artifact and
summary; the matrix aggregate remains the one selected Proof Gate result.

## Proof Quality

A mechanism proof must reject at least one locally plausible but systemically
invalid implementation:

- architecture guards reject authority migration, forbidden dependencies,
  duplicate Owners and widened public APIs;
- race tests control one named interleaving rather than sleeping and hoping;
- Redis atomicity claims use real Redis and an exact `test_*` scope;
- boundary proofs relate independently observed identities, commands, reports
  and transitions without freezing opaque payloads;
- convergence proofs issue each Lab mutation once, first establish its local
  effect, then compare Adapter, Kernel and Task projections;
- Worker Loaded Capacity + Recovery Stability records resource evidence and
  thresholds without claiming functional correctness for every offered
  connection.

Delete or merge a test only when Owner, claim, failure model, evidence boundary
and failure diagnostics are all the same. Keep unique architecture guards,
strict DTO contracts, concurrency races, Redis atomicity and real process
boundaries even if their happy paths overlap.

## Shared Infrastructure

High-level Integration Harnesses use public Runtime and local device/Host APIs,
without production implementation imports or Report injection. Device/Host state
establishes a local mutation only; Network and Scheduling remain independent
observations. Bounded Runtime Preview samples must not become fleet enumeration.

Real Redis proofs use unique `test_*` scopes; the scope is the isolation
contract. Cleanup is best-effort resource hygiene for persistent local Redis,
uses bounded `SCAN` and `UNLINK` for only that exact scope, and never changes a
Proof result. Python runners use the shared
[scope cleanup utility](.github/scripts/cleanup_redis_test_scope.py); Redis protocol
handling stays in `redis-py`, as required by the [runner contract](#lane-index).
GitHub jobs explicitly skip cleanup for their disposable Redis
Service container. A proof that needs a Server or Worker Simulator owns those
process lifecycles and stops all writers before a local cleanup attempt.

Runtime Boundary starts its Server context; Worker Correctness, Dynamic Matching
and Convergence Health own independent Server/Host processes. Use their linked
[Integration contracts](doc/testing/proof-registry.md) for process preconditions,
mutation order, workload, private evidence and observation deadlines. A successful
Harness does not replace a lane's independent runner audit. Loaded Recovery keeps
its separate nightly/manual workflow outside the pull-request Proof Gate.

Android Host owns deterministic tests and library assembly. Android APK Assembly
is independently required when Host or Android Worker Proof is selected and may
run in parallel with Host tests; its artifacts serve the Emulator lane.
[Android Worker Proof](integrations/android-worker-proof/README.md) owns the
Java Harness assertions and shell process choreography. It is not a second
execution of the Java Worker proof.

### Worker Project Fixture

Worker Correctness, Dynamic Matching, Convergence Health, Loaded Recovery and
Android Worker Proof use the explicit `scenario-workers` Project. Runners that
replace Group manifests must also replace the complete Project list under
[Boot's topology contract](server_boot_jvm/README.md#project-topology), preserving
the lane's intended managed Task topology. Each lane owns its workload, fault,
deadline and outcome assertions.

Finite Task creation includes `projectId`. Correctness, Convergence Health and
Android clients lazily read `GET /api/v1/projects/scenario-workers` on first
managed-ID use and cache its Group-to-Task mapping per client instance; they do
not derive IDs or rely on Group registration side effects. Dynamic Matching and
Loaded Recovery create finite Tasks and do not make that Project read.
The separate [Worker Call Performance](integrations/worker-call-performance/README.md)
lane owns its preparation and measurement windows.

## Shared Eligibility refill and dispatch

### Allocation observations and App Checks projection

Changes to notification timing or dispatch isolation select focused Pacer tests.
Routing, queue admission, projection grouping and receiver lifecycle select Server
contracts; projection arithmetic selects App Checks tests. The
[JVM Contracts registry](doc/testing/proof-registry.md#jvm_contracts) owns these
claims, and the [Server Owner](server_jvm/README.md#worker-allocation-observations)
owns the mechanism. Scenario composition and real assignment-window qualification
also select the [existing finite scenario proof](doc/testing/proof-registry.md#product_coexistence);
its execution witness does not establish reliable counting or strict quotas.

### Existing Matching proof ownership

Use [Matching's proof entry](worker_matching_jvm/README.md#cost-failure-and-proof)
for local query, Pool, Properties and index changes, then the
[Redis Owner](doc/testing/proof-registry.md#redis_owner) for atomicity, command
budgets and actual Pacer/Matching composition. Runtime Boundary owns protocol
traversal; [Dynamic Matching](integrations/worker-dynamic-matching/README.md) owns
loaded live-facts execution, and [Coexistence](integrations/scenario-coexistence/README.md)
owns business observations. A qualified Phone migration or another focused pass
does not by itself establish a fix for the separately recorded Dynamic Matching
drain failure or a performance improvement.

## CI Gate

`.github/workflows/proof-ci.yml` runs on pull requests, `main` pushes and
manual dispatch. `.github/proof-paths.yml` selects claim-driven lanes. Manual
dispatch selects every required lane. The final Proof Gate requires each
selected lane to succeed and each unselected lane to be skipped. It checks
Android Host, Android APK Assembly and Android Worker Proof independently, and
uses the aggregate result of both Worker Convergence matrix jobs.

CI does not retry failed proofs. Evidence artifacts contain IDs, relation sets,
counts, state timelines and process logs, never full Worker payloads,
Properties or Task results.

## Deliberate Nonclaims

There is no coverage threshold, flaky-test retry, browser visual matrix,
multi-JDK matrix, Android API matrix, general topology Cartesian product or
soak lane. Correctness lanes do not establish throughput or latency SLAs.
The separate [Worker Call Performance lane](integrations/worker-call-performance/README.md)
measures throughput, latency and saturation; measured performance values are not
correctness thresholds. Protocol combinations remain Runtime Boundary claims.
Physical Android device behavior remains a separate manual proof.

### Candidate Generation Before Rule Qualification

Candidateization, qualification and exact execution fences retain their
[assignment Owner](kernel_pacer_jvm/doc/dispatch/assignment-dispatch-scheduling.md#candidate-generation-boundary).
Select [Redis Owner](doc/testing/proof-registry.md#redis_owner) for actual
Pacer/Matching composition and focused Matching tests for local stock semantics.
The registered claims preserve loss, partial success and no-compensation limits;
repeating a larger workload does not replace those proofs.

### Network Evidence And Candidate Generations

Use the [Worker Score Owner](kernel_jvm/doc/score/worker-score-band-scheduling.md)
and [network timestamp Owner](worker_matching_jvm/README.md#network-evidence-timestamps)
for the two independent commits. Select Redis Owner for exact transitions,
timestamp persistence and command budgets, Pacer contracts for evidence admission,
and Runtime Boundary for actual expiry/reconnect and filter-restart witnesses.
The [Convergence Owner](integrations/worker-convergence-health/README.md) separately
retains its established-fault observations and nonclaims. No lane implies strict
cross-queue ordering, reliable replay or an atomic Facts/Score transaction.
