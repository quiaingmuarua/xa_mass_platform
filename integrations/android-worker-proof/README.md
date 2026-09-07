# Android Worker Proof

`:integrations:android-worker-proof` is the Java 21 proof owner for the real
Android Worker emulator lane. It drives both one Debug App and a fixed three-App
Lab topology through their loopback Host APIs and observes only public Runtime
APIs.

```text
Android Worker Proof
  -> Android device Host API
     -> local lifecycle, event list, active DELAY count
  -> Runtime API
     -> Adapter network projection
     -> Kernel scheduling projection
     -> Direct Call
     -> items:call and results:load
```

The module depends on the transport-neutral Delivery JSON contract only. It
does not import Android Worker, Server, Adapter, Kernel, Redis, or their
implementation types. The emulator shell owns ADB, port mapping, Server and App
processes; Java owns workload, observation, assertions, and evidence.

## Correctness

The correctness proof uses a clean App identity and Redis scope. It closes one
Worker ID across Prepare, Adapter route, Properties observation, Kernel
serviceability, ten sequential `extension.worker.lab.delay` Task Items,
explicit stop/start, and App process restart.

The ten Item identities and `SUCCEEDED` statuses are exact. Result payloads are
opaque. This lane does not claim throughput, concurrent Handler execution, or
device-matrix compatibility.

Task submissions use a 250 millisecond `items:call` observation window so the
accepted message identity returns well inside the HTTP request timeout. A
`NOT_OBSERVED` response is completed by passively waiting on `results:load`;
the Harness never retries an ambiguously timed-out mutation request. A
`FAILED` snapshot is not counted as a successful witness; because late success
may replace failed, bounded observation continues until success or its
deadline.

## Convergence Health

The convergence proof establishes four separate mutations:

1. `extension.worker.lab.fail` returns a Worker `3xxx` outcome while the run and
   subsequent Probe remain usable.
2. A ten-second DELAY enters the Handler, then Adapter close-current closes the
   physical route. The same Worker ID reconnects and the accepted Task Item
   eventually has one visible success result.
3. A thirty-second DELAY enters the Handler, then the Android process is
   force-stopped. Network and Scheduling evidence leave their serviceable
   states; one explicit App start restores the same Worker ID and the original
   Task Item eventually has one visible success result.
4. Runtime Server absence exhausts the Client endpoint budget. The Worker stays
   `STOPPED` after Server restart until the device Host explicitly starts it,
   after which route and scheduling evidence converge again.

The local `activeDelayCount` proves only that the Demo Handler was entered. It
is not Adapter connectivity, Kernel state, or schedulability evidence.

## Phase ownership

| Scenario | Phase | External mutation | Baseline | Oracle |
| --- | --- | --- | --- | --- |
| Correctness | `initial` | none | none | 10/10 success and explicit stop/start |
| Correctness | `process-restart` | force-stop/start | `initial` | identity, Route and HOT recovery |
| Convergence | `active` | Adapter close-current | none | FAIL isolation and transparent reconnect |
| Convergence | `process-loss` | force-stop | `active` | Host, Network and Scheduling become unavailable |
| Convergence | `process-loss-recovery` | start App | `process-loss` | original Task and identity recover |
| Convergence | `terminal` | stop Server | `active` | endpoint exhaustion and local STOPPED |
| Convergence | `server-restart` | restart Server, then explicit Host start | `active` | no automatic start and explicit recovery |
| Triad | `baseline` / `outage` / `recovery` | force-stop/start `lab2` | `baseline` | partial outage isolation |

Java phases own Runtime calls, observations, assertions and Evidence. The shell
performs each ADB or process mutation once after the phase emits its stable
checkpoint marker. A device-local state establishes that a local mutation
happened; Network and Scheduling projections remain independent oracles.

Temporary HTTP connection, read and request-timeout failures from observation
reads may be observed again until the phase deadline. Mutation requests are
issued once and an ambiguous transport failure fails the phase. Invalid JSON,
unexpected HTTP status, invalid state or identity drift fails immediately.
Defaults are 120 seconds for the whole phase and 5 seconds for one request,
with `requestTimeout <= maximumWait`. The phase budget intentionally spans the
default 60-second Serviceability retry plus the final workload witness; it is
not reset for each observation. A phase can exit at most one in-progress
request timeout after its maximum wait budget.

## Three-application isolation

The supplementary Triad proof installs `lab1`, `lab2`, and `lab3` as three
different application IDs on the same emulator. They use independent Android
data sandboxes and Host ports while joining the same `android-demo-workers`
Group and WebSocket Adapter.

Triad Correctness closes three distinct worker IDs and one DELAY Item per
application. Each ON_DEMAND Worker Selector uses the known `workerId` as its exact target;
the independently observed `worker.packageName` Property proves the App-to-
Worker relation but is not part of the Item Worker Selector. Triad
Convergence force-stops only `lab2`, proves
that `lab1` and `lab3` remain connected, schedulable, and executable, then
restarts `lab2` and requires its original worker ID to return. The Triad does
not repeat endpoint exhaustion, Server restart, or in-flight route-loss claims
owned by the single-Worker proof.

## Entrypoints

The Java tasks execute one phase because the shell may need to change an
external process between phases:

```text
./gradlew :integrations:android-worker-proof:runAndroidWorkerCorrectness --args="..."
./gradlew :integrations:android-worker-proof:runAndroidWorkerConvergenceHealth --args="..."
./gradlew :integrations:android-worker-proof:runAndroidWorkerTriadCorrectness --args="..."
./gradlew :integrations:android-worker-proof:runAndroidWorkerTriadConvergenceHealth --args="..."
```

The complete API 33 proof is owned by:

```text
.github/scripts/run_android_emulator_worker.sh
```

Evidence contains identities, states, outcome classes, established mutations,
and relation checks. It excludes Worker Properties, Task payloads, business
Results, screenshots, and video.

## Adding a claim

Before extending this lane:

1. Identify the Primary Owner and keep local mechanism behavior in its Owner
   test.
2. Add Emulator Proof only when the claim requires a physical Android process,
   data sandbox or network fact.
3. Keep API calls, oracle decisions and Evidence in Java.
4. Keep Shell limited to ADB, port mapping and process mutation.
5. Use local Host state only to establish the mutation, never as Adapter or
   Kernel truth.
6. Update this scenario contract, the Proof Registry and proof-path selection
   when the claim boundary changes.

This lane deliberately does not prove dynamic Properties re-Prepare,
Doze/OEM background policy, a device matrix, Handler throughput or arbitrary
application counts. A future Properties proof must use a real owning surface;
it must not add a test-only Properties control API to the Demo Host.

## Verification

```text
./gradlew :integrations:android-worker-proof:test
./gradlew :xa-android:worker-demo:assembleDebug
./gradlew :xa-android:worker-demo:assembleLab1
./gradlew :xa-android:worker-demo:assembleLab2
./gradlew :xa-android:worker-demo:assembleLab3
```

The real proof runs as `Android Worker Proof` in Proof CI on one API 33 x86_64
emulator. `Android APK Assembly` builds and uploads the Debug and three fixed
Lab APKs independently of `Android Host`; the Emulator depends only on that
artifact job. The hosted shell reuses one Gradle daemon with a five-minute idle
timeout for its Java phases without changing App, Server, Redis-scope or
evidence lifecycles. Before installing the Apps, the harness disables Android's
cached-app freezer and reboots the disposable Emulator so all three Lab
processes remain executable during the fixed topology proof. This is
test-environment control, not a background-liveness mechanism. Physical-device
vendor policy and background execution remain manual proofs.
