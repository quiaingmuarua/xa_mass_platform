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

## Convergence Health

The convergence proof establishes three separate mutations:

1. `extension.worker.lab.fail` returns a Worker `3xxx` outcome while the run and
   subsequent Probe remain usable.
2. A ten-second DELAY enters the Handler, then Adapter close-current closes the
   physical route. The same Worker ID reconnects and the accepted Task Item
   eventually has one visible success result.
3. Runtime Server absence exhausts the Client endpoint budget. The Worker stays
   `STOPPED` after Server restart until the device Host explicitly starts it,
   after which route and scheduling evidence converge again.

The local `activeDelayCount` proves only that the Demo Handler was entered. It
is not Adapter connectivity, Kernel state, or schedulability evidence.

## Three-application isolation

The supplementary Triad proof installs `lab1`, `lab2`, and `lab3` as three
different application IDs on the same emulator. They use independent Android
data sandboxes and Host ports while joining the same `android-demo-workers`
Group and WebSocket Adapter.

Triad Correctness closes three distinct worker IDs and one DELAY Item per
application. Each ON_DEMAND rule uses the known `workerId` as its candidate
address and requires the matching `worker.packageName` Property. Triad
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

## Verification

```text
./gradlew :integrations:android-worker-proof:test
./gradlew :xa-android:worker-demo:assembleDebug
./gradlew :xa-android:worker-demo:assembleLab1
./gradlew :xa-android:worker-demo:assembleLab2
./gradlew :xa-android:worker-demo:assembleLab3
```

The real proof runs as `Android Worker Proof` in Proof CI on one API 33 x86_64
emulator. Before installing the Apps, the harness disables Android's cached-app
freezer and reboots the disposable Emulator so all three Lab processes remain
executable during the fixed topology proof. This is test-environment control,
not a background-liveness mechanism. Physical-device vendor policy and
background execution remain manual proofs.
