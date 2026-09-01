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
| Worker Correctness | 2 Groups x 10 Workers, 100 Items | Exact vertical identity, route, extension, Result and restart closure |
| Worker Convergence Health | 2 Groups x 50 Workers, 1000 Items | Finite-time convergence after deterministic state, process and Server faults |
| Worker Capacity | 10,000 Workers | Connection, thread, file-descriptor, memory and reconnect capacity |

Every mechanism claim has one **Primary Proof**. A check repeated elsewhere is
only a prerequisite or Boundary Witness. It must not be described as a second
proof of the same invariant.

Worker Correctness is the deliberately perfect world: its two managed
`items:call` batches require exact response identities and 100 `SUCCEEDED`
statuses while keeping capability Result payloads opaque. Worker Convergence
Health is deliberately imperfect: it fixes offered and invalid-input counts,
includes deterministic slow and failed Handler Items as background offered
work, then asserts independent Network/Scheduling mutations and named witness
convergence. `NOT_OBSERVED` is not treated as failure, and non-witness Items do
not carry a success-count or execution-count oracle.

PRECOMPUTED task-rule and protocol-topology combinations remain Runtime
Boundary claims. High-level Worker proofs use `results:load` only for named
witnesses; `results:export` remains an Owner/boundary and bulk-transfer surface.

## Selection Decision

Use the lowest-cost proof that owns the changed claim:

1. A local algorithm, state transition, validation rule or race changes: run
   the focused Owner test.
2. A DTO, codec, HTTP, Redis or adjacent-owner contract changes: run the
   corresponding Boundary Proof in addition to focused Owner tests.
3. Worker identity, Prepare, long-lived delivery, extensions, Scenario Host or
   Worker-facing Server behavior changes: run Worker Correctness.
4. Kernel/Pacer scheduling, serviceability, finality, Runtime projections or
   Worker fault convergence changes: run Worker Convergence Health.
5. Java Worker connection resource ownership changes: run focused Java Worker
   tests; the 10k lane remains nightly/manual unless the capacity claim itself
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
| Worker WebSocket Scale | `python integrations/worker-websocket-scale/run_worker_websocket_scale.py --workers 10000 --minimum-converged 9900 --redis-url redis://127.0.0.1:6379/15` | Linux, Redis, Java 21 |
| Android Host | Android Debug/Lab builds plus `:integrations:android-worker-proof:test` | Robolectric, MockWebServer, JDK HttpServer |
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

Real Redis proofs use unique `test_*` scopes. Cleanup uses bounded `SCAN` and
`UNLINK` for only that exact scope; it never uses `KEYS`, `FLUSHDB` or
`FLUSHALL`. A proof that needs a Server or Scenario Host owns those process
lifecycles and stops all writers before cleanup.

The Runtime Boundary starts one Java Server context. Worker Correctness and
Worker Convergence Health start Server and Scenario Host as independent
processes. Worker WebSocket Scale is a separate nightly/manual workflow and is
not part of the pull-request Proof Gate.

Android Host is the deterministic SDK and Demo Owner lane. Android Worker Proof
is a separate single-emulator platform lane: its Java Harness owns Correctness
and Convergence assertions, while its shell owns only ADB, Server, App, and
Redis-scope process choreography. Android is not a secondary witness for the
Java Worker proof.

## CI Gate

`.github/workflows/proof-ci.yml` runs on pull requests, `main` pushes and
manual dispatch. `.github/proof-paths.yml` selects claim-driven lanes. Manual
dispatch selects every required lane. The final Proof Gate requires each
selected lane to succeed and each unselected lane to be skipped.

CI does not retry failed proofs. Evidence artifacts contain IDs, relation sets,
counts, state timelines and process logs, never full Worker payloads,
Properties or Task results.

## Deliberate Nonclaims

There is no coverage threshold, flaky-test retry, browser visual matrix,
multi-JDK matrix, Android API matrix, general topology Cartesian product,
throughput benchmark or soak lane. WebSocket, Socket and Polling combinations
remain protocol/Runtime Boundary claims; the 100-Worker health world does not
repeat them. Physical Android device behavior remains a separate manual proof.
