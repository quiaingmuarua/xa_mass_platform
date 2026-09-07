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

## Proof Model

Every mechanism claim has one **Primary Proof**. A repeated check elsewhere is
a prerequisite or Boundary Witness, not a second owner of that invariant.

| Proof | Contract |
| --- | --- |
| Owner Test | Local algorithm, legal transition, strict contract or concurrency fence |
| Boundary Proof | Encoding and behavior across adjacent owners or processes |
| [Worker Correctness](integrations/worker-correctness/README.md) | Exact identity, route, extension, Result and restart closure |
| [Worker Convergence Health](integrations/worker-convergence-health/README.md) | Named witness convergence after established state and process faults |
| [Worker Loaded Capacity + Recovery Stability](integrations/worker-loaded-recovery/README.md) | Sustained work, repeated Server recovery and resource bounds |
| [Android Worker](integrations/android-worker-proof/README.md) | Real Android lifecycle and fixed multi-process isolation |

World size is a fixture, not a proof level. Do not multiply topology, Group or
Adapter cardinality, mutation and workload dimensions into a Cartesian product.
Runtime Boundary owns protocol combinations; named convergence witnesses do not
imply all-offered success. A larger world does not replace correctness or create
a throughput, latency or resource claim. Shared Inventory materialization does
not transfer scenario or oracle ownership.

## Selection Decision

Use the lowest-cost proof that owns the changed claim:

1. A local algorithm, state transition, validation rule or race changes: run
   the focused Owner test.
2. A DTO, codec, HTTP, Redis or adjacent-owner contract changes: run the
   corresponding Boundary Proof in addition to focused Owner tests.
3. Worker identity, Prepare, long-lived delivery, extensions, Scenario Host or
   Worker-facing Server behavior changes: run Worker Correctness.
4. Kernel/Pacer scheduling, serviceability, Runtime projections or Worker fault
   convergence changes: run Worker Convergence Health. TaskItem finality remains
   an Owner and Runtime Boundary claim; Convergence may be selected only as a
   downstream witness.
5. Java Worker connection resource ownership changes: run focused Java Worker
   tests; Worker Loaded Capacity + Recovery Stability remains nightly/manual
   unless its loaded recovery or resource-stability claim must be re-established.
6. Documentation-only changes run Docs Contract. They do not select runtime
   proofs.

Inspect selection for a branch without running a proof:

```powershell
python .github/scripts/explain_proof_selection.py --base origin/main
```

The checked representative-path contract is:

```powershell
python .github/scripts/check_proof_selection.py
```

## Lane Index

| Lane | Primary command | External dependency |
| --- | --- | --- |
| JVM Contracts | Explicit non-Android Gradle module `build` tasks | None |
| Redis Owner | `.\gradlew.bat :server_jvm:redisOwnerIntegrationTest` | Redis 7 |
| Runtime Boundary | `.\gradlew.bat :server_jvm:runtimeBoundaryIntegrationTest` | Redis 7 |
| Worker Correctness | `python integrations/worker-correctness/run_worker_correctness.py --redis-url redis://127.0.0.1:6379/15` | Redis, Server, Scenario Host |
| Worker Convergence Health | `python integrations/worker-convergence-health/run_worker_convergence_health.py --scenario all --redis-url redis://127.0.0.1:6379/15` | Redis, Server, Scenario Host |
| Worker Loaded Capacity + Recovery Stability | `python integrations/worker-loaded-recovery/run_worker_loaded_recovery.py --prepared-workers 15000 --retained-workers 10000 --minimum-initial-converged 14800 --minimum-retained-converged 9900 --workload-items-per-task 5000 --redis-url redis://127.0.0.1:6379/15` | Linux, Redis, Java 21 |
| Android Host | Android unit/library builds plus `:integrations:android-worker-proof:test` | Robolectric, MockWebServer, JDK HttpServer |
| Android APK Assembly | Debug plus three fixed Lab APK variants in Proof CI | Android SDK |
| Android Worker Proof | `Android Worker Proof` in Proof CI | Redis, KVM API 33 Emulator |
| Frontend | `pnpm lint`, `typecheck`, `test`, `build`, `build:demo` | Node, pnpm |
| Runtime Distribution | Distribution integration tests with `-PxaMassVersion=0.5.0` | Redis, Java, Android SDK, Node |
| Docs Contract | `python .github/scripts/check_docs.py` | None |

The exact JVM module build list and Android assembly commands are maintained in
[Proof CI](.github/workflows/proof-ci.yml), alongside their environment setup.
For documentation checks, run both the checker tests and the repository scan:

```powershell
python .github/scripts/test_check_docs.py
python .github/scripts/check_docs.py
python .github/scripts/test_check_proof_selection.py
python .github/scripts/check_proof_selection.py
```

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

Real Redis proofs use unique `test_*` scopes; the scope is the isolation
contract. Cleanup is best-effort resource hygiene for persistent local Redis,
uses bounded `SCAN` and `UNLINK` for only that exact scope, and never changes a
Proof result. GitHub jobs explicitly skip cleanup for their disposable Redis
Service container. A proof that needs a Server or Scenario Host owns those
process lifecycles and stops all writers before a local cleanup attempt.

The Runtime Boundary starts one Java Server context. Worker Correctness and
Worker Convergence Health start Server and Scenario Host as independent
processes. Worker Loaded Capacity + Recovery Stability is a separate
nightly/manual workflow and is not part of the pull-request Proof Gate.

Android Host owns deterministic SDK, capability, Demo and proof-Harness tests
plus Android library assembly. Android APK Assembly independently builds the
Debug and three fixed Lab APKs. It is required when Android Host or Android
Worker Proof is selected, uploads artifacts only for the Emulator, and may run
in parallel with Host tests. Android Worker Proof is a separate single-emulator
platform lane: its Java Harness owns Correctness and Convergence assertions,
including one DELAY Handler process-loss recovery, while its shell owns only
ADB, Server, App, and Redis-scope process choreography. Contract-invalid
observations fail immediately; only temporary HTTP transport failures remain
eligible for bounded polling. Android is not a secondary witness for the Java
Worker proof.

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
multi-JDK matrix, Android API matrix, general topology Cartesian product,
throughput benchmark or soak lane. WebSocket, Socket and Polling combinations
remain protocol/Runtime Boundary claims; the convergence health world does not
repeat them. Physical Android device behavior remains a separate manual proof.
