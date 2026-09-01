# Android Worker Demo

`:xa-android:worker-demo` is the installable Android Host for the XA Mass
local Lab. It composes existing owners instead of copying their mechanisms:

```text
AndroidWorkerDemoApplication
  -> AndroidWorker
     -> persistent client key, Prepare, WebSocket Transport
  -> AndroidDemoCapabilities
     -> extension.worker.android.state.read
     -> extension.worker.android.battery.read
     -> extension.worker.android.string.digest
  -> AndroidWorkerLabEvents
     -> extension.worker.lab.delay
     -> extension.worker.lab.fail
  -> AndroidCapabilityHttpServer
     -> build-variant loopback port
     -> the same business Definitions
     -> local-only host.snapshot / host.start / host.stop Definitions

MainActivity
  -> observes WorkerLifecycle
  -> observes capability Snapshot
```

The App depends only on `transport:android-worker`,
`:xa-android:capabilities`, and `:xa-android:capability-http`. It does not
depend on Server, Kernel, Adapter, Redis, Scenario Workers, or Task
implementation code.

## Runtime behavior

The Application creates and starts one `AndroidWorker` for
`android-demo-workers`. Android Worker owns the long-lived `clientWorkerKey`
and WorkerGroup coordinate, performs one Prepare per explicit start, and
reconnects through the shared Worker Core mechanism. The platform-issued
Worker ID is held only for the current run and resolved again by Server from
the same client key on a later start. The App stores no Worker ID or Endpoint
URI and does not interpret network connection state as Worker online truth.

The Application separately owns `AndroidDemoCapabilities` and
`AndroidWorkerLabEvents`. It passes the three Android capability Definitions and
the two fixed Lab Definitions into both `AndroidWorker.create(...)` and the
local Capability HTTP server. It appends three fixed Host lifecycle Definitions
only for HTTP assembly; those Definitions are never passed to `AndroidWorker`,
never appear in `platform.worker.events.snapshot`, and never become WorkerGroup
capability truth. HTTP startup failure is diagnostic-only and does not prevent
the Worker from starting. The Activity only observes Worker lifecycle,
capability state, and the fixed probe endpoint while visible.
Leaving the Activity stops neither owner; Android may still kill the process
because this demo installs no Service or WorkManager.

The State command returns package/device data plus a persistent local counter.
The Battery command reads `BatteryManager` once per command and returns
availability, capacity percentage, and charging state. It does not install a
battery receiver or background monitor. Both handlers execute synchronously on
the Worker's serialized callback lane.

`extension.worker.lab.delay` strictly accepts
`{"delayMillis":1..30000}` and blocks the current Handler before returning JSON
`null`. `extension.worker.lab.fail` strictly accepts `{}` and produces Worker
outcome `3303` without stopping the run. The Host snapshot exposes
`activeDelayCount` only to establish that DELAY entered the Demo Handler; this
local count is not route, Kernel, or scheduling truth.

The ordinary application ID remains:

```text
com.xa.mass.integration.androidworker
```

The Java namespace is `com.xa.mass.android.workerdemo`. Keeping the
application ID, WorkerGroup ID, Android Worker preference name, and capability
preference name allows an upgrade install to preserve the existing Worker ID
and counter. If the Server Identity registry is reset independently, clear App
data before registering a replacement identity:

```powershell
adb shell pm clear com.xa.mass.integration.androidworker
```

The API 33 proof also builds three fixed Debug-derived Lab variants:

| Variant | applicationId | loopback Host port |
| --- | --- | ---: |
| `lab1` | `com.xa.mass.integration.androidworker.lab1` | 18184 |
| `lab2` | `com.xa.mass.integration.androidworker.lab2` | 18185 |
| `lab3` | `com.xa.mass.integration.androidworker.lab3` | 18186 |

They keep the same WorkerGroup and Runtime endpoints. Separate application
sandboxes provide separate persistent client keys; the existing `packageName`
Worker Property is their scheduling discriminator. This is a fixed proof
topology, not a general replica facility.

## Device-local capability and Host API

The Debug App owns probe port `18084`; each Lab variant uses the port in the
table above. The selected value is passed to the reusable HTTP module. It
listens only on the device loopback address. This path does not require Redis,
the platform Kernel, Java Server, or an Adapter. Install and open the App, then
forward a host loopback port to the device:

```powershell
adb forward tcp:18084 tcp:18084
curl.exe http://127.0.0.1:18084/health
curl.exe http://127.0.0.1:18084/events
curl.exe -X POST `
  -H "Content-Type: application/json" `
  -d "{}" `
  http://127.0.0.1:18084/events/extension.worker.android.state.read:call
curl.exe -X POST `
  -H "Content-Type: application/json" `
  -d "{}" `
  http://127.0.0.1:18084/events/extension.worker.android.battery.read:call
curl.exe -X POST `
  -H "Content-Type: application/json" `
  -d '{"algorithm":"MD5","value":"hello"}' `
  http://127.0.0.1:18084/events/extension.worker.android.string.digest:call
curl.exe -X POST `
  -H "Content-Type: application/json" `
  -d "{}" `
  http://127.0.0.1:18084/events/extension.worker.android-demo.host.snapshot:call
```

These calls update the same processed-command count and last-event observation
shown by the App because HTTP and Worker delivery share one capability
instance. The fixed `extension.worker.android-demo.host.snapshot`, `.start`
and `.stop` Events expose only Demo Host lifecycle control and observation.
They reuse the same finite HTTP dispatcher but are not Worker capabilities.
Local business calls prove capability execution only; the Emulator acceptance
relates them to the real Worker transport separately. Remove the forwarding
rule when finished:

```powershell
adb forward --remove tcp:18084
```

## Real device run

Redis must be available at `redis://localhost:6379/15`. Start the shared Lab
profile; Java Server selects the checked `SCENARIO_LAB` preset and starts the
four Java Kernel Pacer applications:

```powershell
.\gradlew.bat :server_jvm:bootRun `
  --args="--spring.profiles.active=scenario-workers"
```

The profile initializes the advisory `android-demo-workers` catalog and its
registered Task Call, but does not construct the Android Worker. Route the
Runtime API and WebSocket Adapter to the device:

```powershell
adb reverse tcp:18082 tcp:18082
adb reverse tcp:18083 tcp:18083
```

Install and open the App:

```powershell
.\gradlew.bat :xa-android:worker-demo:installDebug
adb shell am start -n `
  com.xa.mass.integration.androidworker/com.xa.mass.android.workerdemo.MainActivity
```

Wait for `RUNNING`, then run the Java correctness phase. It sends ten sequential
DELAY Items through the managed Task and also proves explicit stop/start:

```powershell
.\gradlew.bat :integrations:android-worker-proof:runAndroidWorkerCorrectness `
  --args="--phase=initial --proof-id=manual-android-correctness --evidence-file=build/android-correctness.json --android-api-level=33"
```

The Java proof reads no business Result payload. It relates the App snapshot to
public Adapter Network, Kernel Scheduling, Direct Call, Properties observation,
`items:call`, and `results:load` APIs. The complete process-restart and Server
failure choreography is owned by the hosted shell described below.

## Hosted Emulator proof

`Android Worker Proof` is the primary Android platform E2E lane in Proof CI.
It reuses the Debug and three Lab APKs produced by `Android Host`, boots one API
33 x86_64 Emulator, and uses ADB only to install/start/force-stop the Apps and
map shared Runtime ports plus the four distinct Host ports. All device-side
Worker controls use the three
local Host Events above. The Java 21 `:integrations:android-worker-proof`
module owns workload, assertions, and evidence; the shell owns only Emulator,
ADB, Server, App, and Redis-scope lifecycles.

Correctness proves:

1. one Worker ID closes Prepare, route, Properties, Probe, and Scheduling;
2. ten sequential DELAY Task Items return ten exact `SUCCEEDED` statuses;
3. explicit stop/start retains identity and restores the route;
4. App process restart retains identity and completes another DELAY Item.

Convergence Health proves:

1. FAIL produces Worker `3xxx` while the run and a later Probe remain usable;
2. an in-flight DELAY plus Adapter close-current eventually converges to one
   visible Task success and a connected route for the same Worker ID;
3. Server absence exhausts the Client endpoint budget and leaves the Worker
   `STOPPED`;
4. Server restart does not automatically Prepare; one explicit local start
   restores the original identity, route, Scheduling state, and DELAY witness.

The fixed Triad supplement proves:

1. three application IDs establish three distinct Worker identities in one
   WorkerGroup;
2. identity-bounded Allocation Rules require each App's
   `worker.packageName` before directing one DELAY Item to it;
3. force-stopping `lab2` does not remove service from `lab1` or `lab3`;
4. restarting `lab2` restores its original worker ID and scheduling service.

The disposable API 33 Emulator runs this topology with the cached-app freezer
disabled. The setting prevents Android process-freezing policy from replacing
the Worker isolation mechanism under test; it does not make a background
execution or process-survival claim for production hosts.

The process-restart proof does not relaunch the App as soon as the Adapter
route becomes disconnected. It first observes the stopped Worker leave the
HOT scheduling projection, then relaunches and requires a fresh due-HOT
observation before submitting the Task call. This phase fence prevents stale
pre-restart HOT evidence from being promoted to schedulability.

The terminal phase does not treat Server process exit as immediate Worker
termination. It waits for the Android Client's finite unstable-connection
budget to exhaust before the Worker reaches `STOPPED`. With the current
defaults this includes up to nineteen 500 ms reconnect delays plus the actual
connection-attempt durations; the connect timeout applies to each attempt, not
once to the complete retry sequence. Changes to the Client retry policy must
therefore be reviewed together with the workflow's
`ANDROID_WORKER_MAXIMUM_WAIT_MILLIS` budget.

It asserts IDs, states, Event names, message IDs, counts, established mutations,
and cross-stage relations. Evidence excludes full Properties, business
Payloads, opaque Results, screenshots, and video. The lane does not automate UI.

## Verification

```powershell
.\gradlew.bat :xa-android:worker-demo:testDebugUnitTest
.\gradlew.bat :xa-android:worker-demo:assembleDebug
.\gradlew.bat :xa-android:worker-demo:assembleLab1
.\gradlew.bat :xa-android:worker-demo:assembleLab2
.\gradlew.bat :xa-android:worker-demo:assembleLab3
.\gradlew.bat :xa-android:capability-http:testDebugUnitTest
.\gradlew.bat :xa-android:capability-http:assembleDebug
.\gradlew.bat :integrations:android-worker-proof:test
```

`minSdk 24` is the build baseline. Hosted CI runs both the lightweight Android
Host contract lane and the path-selected API 33 Worker Proof lane. The
documented real-device flow remains the manual proof for vendor behavior,
physical Battery readings, and background execution limits.
