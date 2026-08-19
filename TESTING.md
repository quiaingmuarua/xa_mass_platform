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
| Redis Owner | Java Redis providers plus Server-owned Identity and Binding preserve their real Redis contracts; the Serviceability bridge also proves destructive Adapter request consume and bounded Adapter-evidence append | Redis 7 | `./gradlew :server_jvm:redisOwnerIntegrationTest` |
| Runtime Boundary | Python Kernel, Java Server, Polling, WebSocket, and Socket close real Task paths; WebSocket also proves DIRECT_CALL, exact Adapter Route evidence through the Kernel Result Pacer, periodic snapshot compensation, and current score convergence | Redis 7 and Python Kernel | `./gradlew :server_jvm:runtimeBoundaryIntegrationTest` |
| Task Batch | The checked profile batch-runs six WorkerGroup/Event cases through long-lived Tasks, preserves 20 Worker identities, and returns 60 results | Redis 7, Python Kernel, Java Server | `./gradlew :integrations:worker-capability-rpc:runRpcScenario` |
| Android Host | Android assembly, concrete capability Definitions, loopback Capability HTTP, Register/Bind, local WebSocket protocol, demo host, and host RPC driver remain compatible | Robolectric and MockWebServer | Android Debug tasks plus host Python tests |
| Frontend | The read-only Runtime views and Task Batch Lab public-API flow remain lint-clean, type-safe, unit-tested, and buildable | Node and pnpm | `pnpm lint`, `typecheck`, `test`, `build` |
| Docs Contract | Current documentation entrypoints, relative links, stable overview sections, and retired contract vocabulary remain converged | None | `python .github/scripts/check_docs.py` |

The `Task Batch` command uploads two text fixtures, executes six explicit
WorkerGroup/Event/Payload-key batches, downloads six successful JSONL outputs,
and validates exactly 60 results. The Lab proof
additionally requires 20 persistent, globally unique canonical Worker IDs. A
result is not attributed to a particular Worker.

## Local Commands

Server integration proofs use the checked `integration-test` profile:

```text
Python Kernel  http://127.0.0.1:18080
Redis          redis://127.0.0.1:6379/15
```

The profile configures addresses but does not start either dependency. Strict
proof tasks fail with the underlying connection error when a dependency is not
running.

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
  :integrations:worker-capability-rpc:build
```

Redis Owner:

```powershell
.\gradlew.bat :server_jvm:redisOwnerIntegrationTest
```

Runtime Boundary requires a healthy Python Kernel at `18080`. Start that
process with the checked proof configuration so the Serviceability Result
Pacer is active:

```powershell
$env:XA_MASS_WORKER_PROPERTY_INDEX_REGISTRY_JSON = `
  '{"index.worker.region":"redis-hash","index.platform.pool":"redis-hash"}'

python -m kernel_design.runtime_server `
  --host 127.0.0.1 --port 18080 `
  --config server_jvm/src/test/resources/kernel-runtime-boundary-config.json

.\gradlew.bat :server_jvm:runtimeBoundaryIntegrationTest
```

The same proof invokes an unpaused real WebSocket Worker through DIRECT_CALL,
exercises a custom SYSTEM handler and the default probe/properties/events
handlers, observes and closes its current Adapter Channel, and proves
transparent reconnect. A dedicated Worker also proves the full current
Serviceability behavior:

```text
physical WebSocket connect
  -> exact CONNECTED evidence -> Kernel Result Pacer -> newer HOT score
explicit Worker shutdown
  -> exact DISCONNECTED evidence -> Kernel Result Pacer -> RECOVERY score
same Worker identity reconnect
  -> exact CONNECTED evidence -> Kernel Result Pacer -> newer HOT score
periodic request
  -> Adapter snapshot evidence -> Kernel Result Pacer -> newer check time
```

This is both cross-boundary mechanism proof and behavior proof for the current
policy. It deliberately does not freeze evidence age, discovery cadence,
retry/cold policy, or the final meaning of positive and negative evidence. It
also does not prove distributed Direct Call waiter correlation, Binding
generation fencing, clock synchronization, or reliable evidence delivery.

Task Batch process startup and the Android real-device acceptance procedure
are documented by their owning modules:

- [`integrations/worker-capability-rpc`](integrations/worker-capability-rpc/README.md)
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

Frontend:

```powershell
Set-Location frontend
corepack pnpm install --frozen-lockfile
corepack pnpm lint
corepack pnpm typecheck
corepack pnpm test
corepack pnpm build
```

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
schemas and stores plus the Task Batch Lab's strict response schemas, public
upload/run/download routes, configured Group/Event selection, and Mock-mode
no-call boundary. It does not run visual regression or browser
compatibility suites.

## CI Selection and Gate

`.github/workflows/proof-ci.yml` runs for every pull request, every main push,
and manual dispatch. Its first job selects proof lanes from changed paths.
Manual dispatch selects every lane. The final `Proof Gate` succeeds only when
every selected lane succeeds and every unselected lane is explicitly skipped.

Representative selection rules:

- Docs Contract runs on every workflow invocation; documentation-only changes
  otherwise reach only `Proof Gate`;
- the human overview additionally runs the Frontend lane;
- Netty Adapter changes run JVM Contracts, Runtime Boundary, and Task Batch;
- Task-Batch-only changes run JVM Contracts and Task Batch;
- Android-only changes run Android Host;
- Worker Core or Delivery contract changes also run their downstream Runtime,
  Task Batch, and Android proofs;
- Kernel executable-spec changes run the Oracle plus JVM parity, Redis,
  Runtime, and Task Batch proofs.

CI uploads failed JUnit reports and process logs for seven days. Task Batch
uploads its complete or partial JSONL evidence on every run. Failures are not
automatically retried.

## Deliberate Non-Goals

There is no coverage threshold, multi-JDK or multi-OS matrix, flaky-test retry,
browser visual regression, Android emulator gate, performance test, or soak
lane. The Android real-device WorkerGroup RPC is a manual acceptance proof and must
not be represented as a normal hosted-runner E2E test.
