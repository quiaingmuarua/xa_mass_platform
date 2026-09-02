# XA Mass Proof Selection

Status: current repository proof-lane registry entrypoint.

Tests are organized by mechanism claim, not by coverage percentage. A test is
valuable when it is the cheapest credible proof of one named invariant or
boundary. Repeating the same success path at another scale does not create a
new claim.

Detailed lane ownership is in
[`doc/testing/proof-registry.md`](doc/testing/proof-registry.md). The fixed
Worker worlds are in
[`doc/testing/worker-proof-scenarios.md`](doc/testing/worker-proof-scenarios.md).

## Proof Model

| Level | Typical scale | Primary purpose |
| --- | ---: | --- |
| Owner Test | minimal | One Owner algorithm, legal transition, strict contract or concurrency fence |
| Boundary Proof | minimal | Encoding and behavior between two adjacent Owners or processes |
| Worker Correctness | 2 Groups x 50 Workers, 100 Items | Exact vertical identity, route, extension, Result and restart closure |
| Worker Convergence Health | 2 Groups x 50 Workers, 1000 Items | Finite-time convergence after deterministic state, process and Server faults |
| Worker Capacity | 15,000 prepared / 10,000 active Workers, 10 x 5,000 Items per phase | Connection headroom, Fleet contraction, loaded work, resources and reconnect capacity |

Every mechanism claim has one **Primary Proof**. A check repeated elsewhere is
only a prerequisite or Boundary Witness. It must not be described as a second
proof of the same invariant.

Worker Correctness is the deliberately perfect world: its two managed
`items:call` batches require exact response identities and 100 `SUCCEEDED`
statuses while keeping capability Result payloads opaque. Worker Convergence
Health is deliberately imperfect: it fixes offered and invalid-input counts,
includes deterministic slow and failed Handler Items as background offered
work, then asserts independent Network/Scheduling mutations and named witness
convergence. `NOT_OBSERVED` is not treated as failure, `FAILED` is not counted
as a successful witness, and non-witness Items do not carry a success-count or
execution-count oracle.

Correctness and Convergence materialize the same canonical 100-Worker
Inventory into isolated Lab roots. Capacity uses the same materialization
contract for 15,000 identities, then deterministically retains 10,000 active
Workers; only topology, capability assembly and proof oracle change between
lanes.

PRECOMPUTED task-rule and protocol-topology combinations remain Runtime
Boundary claims. Convergence Health uses `results:load` only for named
witnesses. Capacity pages `results:load` for ten bounded Task progress streams
and performs one final export per Task; this is the explicit bulk result-path
witness rather than a business-payload oracle.

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
   tests; the 15k/10k lane remains nightly/manual unless the capacity claim itself
   must be re-established.
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
| Worker WebSocket Scale | `python integrations/worker-websocket-scale/run_worker_websocket_scale.py --prepared-workers 15000 --retained-workers 10000 --minimum-initial-converged 14800 --minimum-retained-converged 9900 --workload-items-per-task 5000 --redis-url redis://127.0.0.1:6379/15` | Linux, Redis, Java 21 |
| Android Host | Android unit/library builds plus `:integrations:android-worker-proof:test` | Robolectric, MockWebServer, JDK HttpServer |
| Android APK Assembly | Debug plus three fixed Lab APK variants in Proof CI | Android SDK |
| Android Worker Proof | `Android Worker Proof` in Proof CI | Redis, KVM API 33 Emulator |
| Frontend | `pnpm lint`, `typecheck`, `test`, `build`, `build:demo` | Node, pnpm |
| Runtime Distribution | Distribution integration tests with `-PxaMassVersion=0.5.0` | Redis, Java, Android SDK, Node |
| Docs Contract | `python .github/scripts/check_docs.py` | None |

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
- capacity proofs record resource evidence and thresholds without claiming
  functional correctness for every offered connection.

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
processes. Worker WebSocket Scale is a separate nightly/manual workflow and is
not part of the pull-request Proof Gate.

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
remain protocol/Runtime Boundary claims; the 100-Worker health world does not
repeat them. Physical Android device behavior remains a separate manual proof.
