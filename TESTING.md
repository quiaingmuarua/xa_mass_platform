# Proof Lanes

Status: current repository verification contract.

Tests in this repository are organized by the invariant they prove, not by a
coverage target. Deterministic owner tests run separately from real Redis and
cross-process acceptance proofs. A green lane is meaningful only for the
boundary named below.

## Proof Registry

| Lane | Invariant | External dependencies | Primary command |
| --- | --- | --- | --- |
| Kernel Oracle | Python executable spec remains the mechanism oracle | Redis 7 | `python -m unittest discover -s kernel_design/executable_spec/tests` |
| JVM Contracts | JVM modules compile and their owner, codec, architecture, and unit proofs pass | None | Explicit non-Android Gradle module `build` tasks |
| Redis Owner | Java Redis providers plus Server-owned Identity and Binding preserve their real Redis contracts; Task create/lifecycle/Call submission prove the six-operation Java Task Score slice, and the Serviceability bridge proves Adapter request consume and evidence append | Redis 7 | `./gradlew :server_jvm:redisOwnerIntegrationTest` |
| Runtime Boundary | One Java Server context owns the temporary Python Pacer CLI child while a finite polling Task closes and exports its success Results, and WorkerGroup registration returns a managed Task ID whose Task-addressed Calls synchronously return through WebSocket and Socket; WebSocket also proves DIRECT_CALL, Adapter Network observation and Serviceability convergence | Redis 7, Python plus `redis` dependency | `./gradlew :server_jvm:runtimeBoundaryIntegrationTest` |
| Worker Fleet | The standalone Scenario Host creates two fixed ten-replica Groups whose Lab client keys, Runtime Preview identities, Adapter routes, probe execution, Properties observation, and Host-restart mapping close over the same 20 Worker IDs while Server/Pacer stay up | Redis 7, Python plus `redis` dependency, Java Server and Scenario Worker Host | `./gradlew :integrations:worker-fleet-acceptance:runFleetAcceptance` twice |
| Capability Task | An external Java client creates two finite Tasks, turns two local ten-line fixtures into 60 ordinary Items across six WorkerGroup/Event combinations, approves the Tasks, and correlates 60 exported success Results | Redis 7, Python plus `redis` dependency, Java Server and Scenario Worker Host | `./gradlew :integrations:worker-capability-task:runCapabilityTaskScenario` |
| Android Host | Android assembly, concrete capability Definitions, loopback Capability HTTP, Prepare, local WebSocket protocol, demo host, and host RPC driver remain compatible | Robolectric and MockWebServer | Android Debug tasks plus host Python tests |
| Android Emulator Worker | One API 33 Demo App closes local Host control, Worker identity, Adapter route, Direct Call, Properties observation, WorkerGroup execution, endpoint terminal, explicit restart, and process-restart identity relations | Redis 7, Python plus `redis` dependency, Java Server, API 33 x86_64 Emulator | `Android Emulator Worker` in `.github/workflows/proof-ci.yml` |
| Frontend | The read-only Runtime views and finite Task create/append/approve/export flow remain lint-clean, type-safe, unit-tested, and buildable | Node and pnpm | `pnpm lint`, `typecheck`, `test`, `build` |
| Runtime Distribution | One versioned ZIP contains matching Server JAR, production-only Pacer wheel, hash-locked offline dependency, frontend, optional standalone Worker Host and schema-v2 manifest; outside the repository the Server reaches readiness with zero JVM Workers, the separate Host closes a managed Task Call, and stopping Host leaves Server ready | Redis 7, Java 21, Python 3.11.3 (minimum supported), Node 22.19 and pnpm 11.9 | `./gradlew :distribution:server:runtimeDistributionTest -PxaMassVersion=0.2.0` |
| Docs Contract | Current documentation entrypoints, relative links, stable overview sections, and retired contract vocabulary remain converged | None | `python .github/scripts/check_docs.py` |

The `Capability Task` command reads two local text fixtures, creates two
finite Tasks, appends 30 Items to each, approves them, downloads two successful
JSONL exports, and validates exactly 60 results, ten per expected Group/Event
combination, with globally unique Message IDs. It does not attribute a result to a
particular Worker or freeze capability-specific Result fields and values.

Owner and capability unit tests remain the strict place for DTO shape,
validation rules, and exact business values such as phone parsing, digest, and
Base64 semantics. Cross-process acceptance instead fixes stable relationships:
identity sets, counts, Group/Event ownership, correlation, protocol outcomes,
and restart continuity. This lets compatible payload or Properties evolution
proceed without weakening the mechanism proof.

## Proof Quality Contract

Compilation, formatting, simple codec examples and local success cases are the
repository hygiene floor. They remain cheap and useful, but their count does
not widen a lane's proof claim. A mechanism proof must reject at least one
locally plausible but systemically invalid implementation:

- architecture proofs reject authority migration, forbidden dependencies,
  widened public APIs, duplicate state owners and hidden execution resources;
- concurrency proofs control a named interleaving such as stop during prepare,
  terminal during command execution, stale callback after replacement, or
  concurrent Route replacement;
- owner proofs exercise the canonical state operation and, for Redis atomicity
  claims, use real Redis rather than a mirrored in-memory implementation;
- boundary and acceptance proofs relate independently observed identities,
  routes, commands, results and lifecycle transitions without freezing opaque
  payloads or repeating a success case as a load claim.

Every new proof must name its invariant, failure model and deliberate
non-goals. Repeating Probe, Event or Task success does not add evidence unless
the repetition introduces a distinct owner, process boundary, interleaving or
capacity invariant.

| Mechanism | Static owner guard | Controlled race/failure proof | Real boundary proof | Not claimed |
| --- | --- | --- | --- | --- |
| Worker run and text protocol | Core dependency, API and zero-resource architecture tests | prepare/stop/close, terminal/result, executor rejection and stale-Transport suppression | Runtime Boundary and Android Emulator | reliable Result delivery after endpoint loss |
| Adapter Route and Properties observation | Netty owner/package and projection separation tests | duplicate Bind, stale Channel callback, replacement Route and bounded retention | Runtime Boundary, Worker Fleet and Android Emulator | distributed Route truth |
| Worker Identity and Binding | Server owner boundaries and Redis provider contracts | duplicate/invalid registration and binding owner cases | Redis Owner plus Fleet/Android identity continuity | multi-Server Binding generation fencing |
| Java Group replicas | fixed-topology Manager and Host-resource architecture tests | desired/actual divergence, partial failure and reverse close | Worker Fleet | dynamic scaling or automatic reconcile |
| Task and Result correlation | Kernel owner/oracle plus Server boundary tests | owner-local retry, finality, scan and correlation cases | Runtime Boundary and Capability Task | throughput, fairness, soak or crash recovery |
| Android process lifecycle | Android Worker/Host architecture and deterministic Host tests | Client callback, stop and terminal cases | Android Emulator | vendor background policy, Doze or physical-device behavior |

The separate `Worker Fleet` lane first starts Server and proves both JVM Group
previews are empty, then starts the real standalone Scenario Host on an
isolated, initially absent Lab root. Its initial phase relates the exact configured
client keys to 20 unique Worker IDs read through Runtime Preview, connected
Adapter routes, one probe
Result per target, and same-round Worker/Adapter Properties observations. CI
then terminates the Host, requires Server and its Pacer child to remain ready,
restarts the same Host with Redis, Kernel, Server and Lab retained, and requires
the complete client-key-to-Worker-ID mapping and all live relationships to
close again.

The `Android Emulator Worker` lane installs the Demo APK on one API 33
Emulator. ADB is limited to installation, process lifecycle, and loopback port
mapping. Device-local lifecycle control and observation use the Demo's fixed
NanoHTTP Events; Worker and Adapter observations use public Runtime APIs. The
proof checks initial execution and Properties observation, explicit
stop/start, retry exhaustion while the Server is down, absence of automatic
restart, explicit recovery, and Worker ID continuity after App process
restart. It fixes relationship and count invariants without storing Battery,
device Properties, opaque Results, or business Payloads.

## Local Commands

Server integration proofs use the checked `integration-test` profile:

```text
Java readiness  http://127.0.0.1:<test-port>/actuator/health/readiness
Redis           redis://127.0.0.1:6379/15
Redis scope     test_runtime_boundary_<unique run token>
```

The test starts one Java Spring context. Its `KernelPacerAssembly` starts the
checked Python CLI with the Runtime Boundary Pacer configuration and waits for
the exact readiness token. Redis remains an external dependency; failure to
start the child or connect to Redis fails the proof. Each run generates one
unique `test_*` scope and injects that exact scope into the child, so the
cross-process proof cannot pass through matching defaults. It plants a sentinel
in a different scope and proves the sentinel survives. After the Spring context
and its Pacer child stop, cleanup uses cursor `SCAN` plus bounded `UNLINK` only
for the exact run-owned scope; it never clears Redis DB 15.

Redis Owner tests and Python Redis proofs generate the same kind of exact scope
inside their fixtures. Worker Fleet, Capability Task, and Android Emulator CI set
`XA_MASS_REDIS_SCOPE=test_<lane>_<runId>_<attempt>` on the complete Server/Pacer
process trees, retain that scope across an intentional restart, then clean only
that scope after all writers stop. A proof may share the URL and DB with a
running `profile_*` environment; neither side can see or delete the other's
data.

The same lane also runs a controlled no-network Python child to prove exact
ready-token startup, exit-before-ready, readiness timeout, stdin-EOF shutdown,
forced shutdown, idempotent cleanup, and removal of owner/ready files. Unit
proofs keep disk-state recovery non-destructive: dead or PID-reused owner state
is cleaned, while a matching live process blocks startup and remains untouched.
The configured Group/Task and Adapter assembly has an explicit higher lifecycle
phase, so it starts after the child and closes before it. The standalone
Scenario Worker Host is outside the Spring lifecycle.

The Python Kernel Oracle is a separate proof owner and still uses its explicit
Redis test switch:

```powershell
$env:KERNEL_DESIGN_REDIS_URL = "redis://127.0.0.1:6379/15"
python -m unittest discover -s kernel_design/executable_spec/tests
python -m compileall -q kernel_design/executable_spec
```

Non-Android JVM contracts:

```powershell
.\gradlew.bat --continue `
  :transport:worker-delivery-contract:build `
  :kernel_jvm:build `
  :transport:worker-core:build `
  :transport:java-worker:build `
  :transport:netty-adapter:build `
  :scenario_workers_jvm:build `
  :server_jvm:build `
  :integrations:worker-capability-task:build `
  :integrations:worker-fleet-acceptance:build
```

Redis Owner:

```powershell
.\gradlew.bat :server_jvm:redisOwnerIntegrationTest
```

Runtime Boundary starts only the Java test context. Its configured child runs
assignment, Result Routing and Serviceability Pacers; Task business calls
remain Java-to-Redis:

```powershell
.\gradlew.bat :server_jvm:runtimeBoundaryIntegrationTest
```

The same proof invokes an unpaused real WebSocket Worker through DIRECT_CALL,
exercises a custom SYSTEM handler and the default probe/properties/events
handlers, observes the current Adapter Channel through the public Runtime View,
closes it through DIRECT_CALL, and proves transparent reconnect. Finite Tasks
prove the generic create/approve/close surface through Polling and the
terminal-only success Result export route.
Separate WebSocket and Socket Workers each prove WorkerGroup registration with
its returned managed Task ID, idempotent aggregate re-registration, two
consecutive bounded Task-ID-addressed calls, the shared Task result read, and
generic lifecycle isolation of the managed Task.
A dedicated Worker also proves the full current Serviceability behavior:

The checked Runtime Boundary config uses a deliberately short recovery retry
interval so periodic compensation remains a bounded test; it is not a
production policy default.

```text
physical WebSocket connect
  -> exact CONNECTED evidence -> Kernel Result Pacer -> HOT at/above epoch floor
explicit Worker shutdown
  -> exact DISCONNECTED evidence -> Kernel Result Pacer -> exact RECOVERY toggle
same Worker identity reconnect
  -> exact CONNECTED evidence -> Kernel Result Pacer -> HOT at/above epoch floor
due RUNNING_VISIBLE Task demand
  -> Kernel derives its WorkerGroup -> periodic Adapter snapshot evidence
  -> Kernel Result Pacer -> RECOVERY-to-HOT compensation
expired TASK delivery
  -> 23002 Task Result and separate KERNEL evidence use independent Pacers
```

This is both cross-boundary mechanism proof and behavior proof for the current
policy. It deliberately does not freeze evidence age, discovery cadence,
retry/cold policy, or the final meaning of positive and negative evidence. It
also does not prove distributed Direct Call waiter correlation, Binding
generation fencing, clock synchronization, or reliable evidence delivery.

Worker Fleet, Capability Task process startup, and Android Emulator/real-device
acceptance are documented by their owning modules:

- [`integrations/worker-fleet-acceptance`](integrations/worker-fleet-acceptance/README.md)
- [`integrations/worker-capability-task`](integrations/worker-capability-task/README.md)
- [`xa-android/worker-demo`](xa-android/worker-demo/README.md)

Android Host CI-equivalent proof:

```powershell
.\gradlew.bat --continue `
  :transport:android-worker:testDebugUnitTest `
  :transport:android-worker:assembleDebug `
  :xa-android:capabilities:testDebugUnitTest `
  :xa-android:capabilities:assembleDebug `
  :xa-android:capability-http:testDebugUnitTest `
  :xa-android:capability-http:assembleDebug `
  :xa-android:worker-demo:testDebugUnitTest `
  :xa-android:worker-demo:assembleDebug
python -m unittest discover `
  -s xa-android/worker-demo/host `
  -p "test_*.py"
```

The hosted Emulator proof is intentionally workflow-owned because it requires
KVM, an APK artifact from `Android Host`, Redis, Kernel and Server process
orchestration, and ADB port mapping. Its checked orchestration is
`.github/scripts/run_android_emulator_worker.sh`; its standard-library
acceptance driver is
`xa-android/worker-demo/host/android_worker_acceptance.py`.

Frontend:

```powershell
Set-Location frontend
corepack pnpm install --frozen-lockfile
corepack pnpm lint
corepack pnpm typecheck
corepack pnpm test
corepack pnpm build
```

Runtime Distribution builds every publishable component, checks the archive
ABI and opens the Kernel wheel to reject `tests` and `test_support`. Its real
proof extracts the ZIP to a temporary directory outside the checkout, removes
`PYTHONPATH`, creates the archive-owned venv with `--no-index`, starts the
packaged Server against one unique `test_*` scope, loads Scalar and Frontend,
proves both JVM Worker previews are empty, starts the packaged Host separately,
and closes one managed String Task Call. It then stops only the Host, proves
Server readiness remains UP, stops Server, and cleans only that exact scope:

```powershell
.\gradlew.bat :distribution:server:runtimeDistributionTest `
  "-PxaMassVersion=0.2.0"
```

This lane proves the deployable Server boundary and optional finite Lab Host
packaging, not Worker SDK publication, an OCI image, Redis lifecycle, or an
Android device.

Documentation contract:

```powershell
python .github/scripts/check_docs.py
git diff --check
```

The documentation checker uses only the Python standard library. It validates
current tracked Markdown and the source `frontend/public/overview.htm`; it
excludes historical `doc/archive` content and does not generate documentation
or access the network.

The frontend proof keeps UI testing deliberately small. It validates Runtime
schemas and stores plus TXT validation, stable message IDs, 100-Item chunks,
the public create/append/approve/export sequence, 202 retry behavior, and the
Mock-mode no-call boundary. It does not run visual regression or browser
compatibility suites.

## CI Selection and Gate

`.github/workflows/proof-ci.yml` runs for every pull request, every main push,
and manual dispatch. Its first job selects proof lanes from changed paths.
Manual dispatch selects every lane. The final `Proof Gate` succeeds only when
every selected lane succeeds and every unselected lane is explicitly skipped.

Selection rules live in `.github/proof-paths.yml`, separate from Workflow
orchestration. Before selecting a lane, CI runs the standard-library
`check_proof_selection.py` contract. It requires every positive pattern to
match a repository file and verifies representative owner paths against their
exact expected lane sets. The selector uses `some-with-excludes`, so the shared
negative Markdown rule actually overrides matching implementation directories.
A selection-contract or Workflow change selects every implementation lane.

```powershell
python -m unittest discover `
  -s .github/scripts `
  -p "test_check_proof_selection.py"
python .github/scripts/check_proof_selection.py
```

Representative selection rules:

- Docs Contract runs on every workflow invocation; documentation-only changes
  otherwise reach only `Proof Gate`;
- the human overview additionally runs the Frontend lane;
- Netty Adapter changes run JVM Contracts, Runtime Boundary, Worker Fleet, and
  Capability Task; WebSocket/Route production changes also run Android Emulator
  Worker;
- Capability-Task-only changes run JVM Contracts and Capability Task;
- Worker-Fleet-only changes run JVM Contracts and Worker Fleet;
- Android test-only changes run Android Host; Android production, Manifest,
  build, or Emulator driver changes also run Android Emulator Worker;
- Worker Core or Delivery contract changes also run their downstream Runtime,
  Worker Fleet, Capability Task, Android Host, and Android Emulator proofs;
- Kernel executable-spec changes run the Oracle plus JVM parity, Redis,
  Runtime, Worker Fleet, Capability Task, and Runtime Distribution proofs;
- production inputs embedded in the Server archive run Runtime Distribution;
  test-only changes in those modules do not select it.

CI uploads failed JUnit reports and process logs for seven days. Worker Fleet,
Capability Task, and Android Emulator upload only schema-versioned safe evidence:
IDs, relation sets, run summaries, counts, and differences. Android adds
filtered logs but no screenshot or video. These lanes do not upload full
Worker Result, Task output, Properties content, or business Payload. Failures
are not automatically retried.

## Deliberate Non-Goals

There is no coverage threshold, multi-JDK or multi-OS matrix, flaky-test retry,
browser visual regression, Android API matrix, UI automation, performance
test, or soak lane. The Android real-device managed Task Call remains a separate
manual proof for vendor systems, physical Battery behavior, and background
execution limits; the hosted Emulator lane does not claim those properties.
