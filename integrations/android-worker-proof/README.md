# Android Worker Proof

`:integrations:android-worker-proof` is the Java 21 proof owner for one real
Android Worker process. It drives the existing `:xa-android:worker-demo` APK
through its loopback Host API and observes only public Runtime APIs.

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

## Entrypoints

The Java tasks execute one phase because the shell may need to change an
external process between phases:

```text
./gradlew :integrations:android-worker-proof:runAndroidWorkerCorrectness --args="..."
./gradlew :integrations:android-worker-proof:runAndroidWorkerConvergenceHealth --args="..."
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
```

The real proof runs as `Android Worker Proof` in Proof CI on one API 33 x86_64
emulator. Physical-device vendor policy and background execution remain manual
proofs.
