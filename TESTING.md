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
| Redis Owner | Java Redis providers plus Server-owned Identity and Binding preserve their real Redis contracts | Redis 7 | `./gradlew :server_jvm:redisOwnerIntegrationTest` |
| Runtime Boundary | Python Kernel, Java Server, Polling, WebSocket, and Socket close real Task paths | Redis 7 and Python Kernel | `./gradlew :server_jvm:runtimeBoundaryIntegrationTest` |
| Scenario RPC | The checked profile runs six Server-owned file Scenarios, preserves 20 Worker identities, and returns 60 results | Redis 7, Python Kernel, Java Server | `./gradlew :integrations:worker-capability-rpc:runRpcScenario` |
| Android Host | Android assembly, Register/Bind, local WebSocket protocol, demo host, and host RPC driver remain compatible | Robolectric and MockWebServer | Android Debug tasks plus host Python tests |
| Frontend | The read-only Worker and configured Task Runtime views remain lint-clean, type-safe, unit-tested, and buildable | Node and pnpm | `pnpm lint`, `typecheck`, `test`, `build` |

The `Scenario RPC` command uploads two text fixtures, runs the six finite
Server Scenarios, downloads six JSONL outputs, and validates exactly 60
results. The Lab proof additionally requires 20 persistent, globally unique
canonical Worker IDs. A result is not attributed to a particular Worker.

## Local Commands

Set the real Redis location before running Redis-backed proofs:

```powershell
$env:KERNEL_DESIGN_REDIS_URL = "redis://127.0.0.1:6379/15"
```

Kernel Oracle:

```powershell
python -m unittest discover -s kernel_design/executable_spec/tests
python -m compileall -q kernel_design/executable_spec
```

Non-Android JVM contracts:

```powershell
.\gradlew.bat --continue `
  :worker_delivery_contract_jvm:build `
  :kernel_jvm:build `
  :scenario_rpc_jvm:build `
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

Runtime Boundary additionally requires a healthy Python Kernel at `18080`:

```powershell
$env:KERNEL_COMMAND_INTEGRATION_URL = "http://127.0.0.1:18080"
.\gradlew.bat :server_jvm:runtimeBoundaryIntegrationTest
```

Scenario RPC process startup and the Android real-device acceptance procedure
are documented by their owning integration modules:

- [`integrations/worker-capability-rpc`](integrations/worker-capability-rpc/README.md)
- [`integrations/android-websocket-worker`](integrations/android-websocket-worker/README.md)

Android Host CI-equivalent proof:

```powershell
.\gradlew.bat --continue `
  :transport:android-worker:testDebugUnitTest `
  :transport:android-worker:assembleDebug `
  :integrations:android-websocket-worker:testDebugUnitTest `
  :integrations:android-websocket-worker:assembleDebug
python -m unittest discover `
  -s integrations/android-websocket-worker/host `
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

## CI Selection and Gate

`.github/workflows/proof-ci.yml` runs for every pull request, every main push,
and manual dispatch. Its first job selects proof lanes from changed paths.
Manual dispatch selects every lane. The final `Proof Gate` succeeds only when
every selected lane succeeds and every unselected lane is explicitly skipped.

Representative selection rules:

- documentation-only changes run only selection and `Proof Gate`;
- Netty Adapter changes run JVM Contracts, Runtime Boundary, and Scenario RPC;
- Scenario-only changes run JVM Contracts and Scenario RPC;
- Android-only changes run Android Host;
- Worker Core or Delivery contract changes also run their downstream Runtime,
  Scenario, and Android proofs;
- Kernel executable-spec changes run the Oracle plus JVM parity, Redis,
  Runtime, and Scenario proofs.

CI uploads failed JUnit reports and process logs for seven days. Scenario RPC
uploads its complete or partial JSONL evidence on every run. Failures are not
automatically retried.

## Deliberate Non-Goals

There is no coverage threshold, multi-JDK or multi-OS matrix, flaky-test retry,
browser visual regression, Android emulator gate, performance test, or soak
lane. The Android real-device WorkerGroup RPC is a manual acceptance proof and must
not be represented as a normal hosted-runner E2E test.
