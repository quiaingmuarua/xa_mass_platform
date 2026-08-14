# Android Worker Demo

`:xa-android:worker-demo` is the installable Android Host for the XA Mass
local Lab. It composes existing owners instead of copying their mechanisms:

```text
AndroidWorkerDemoApplication
  -> AndroidWorker
     -> persistent Worker identity, Register/Bind, WebSocket Transport
  -> AndroidDemoCapabilities
     -> android.state.read
     -> android.battery.read
     -> android.string.digest
  -> AndroidCapabilityHttpServer
     -> 127.0.0.1:18084
     -> direct calls through the same capability Definitions

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
and platform-issued Worker ID, performs Register/Bind, and reconnects through
the shared Worker Core mechanism. The App stores no Endpoint URI and does not
interpret network connection state as Worker online truth.

The Application separately owns `AndroidDemoCapabilities` and passes its two
immutable Definitions into both `AndroidWorker.create(...)` and the local
Capability HTTP probe. HTTP startup failure is diagnostic-only and does not
prevent the Worker from starting. The Activity only observes Worker lifecycle,
capability state, and the fixed probe endpoint while visible. Leaving the
Activity stops neither owner; Android may still kill the process because this
demo installs no Service or WorkManager.

The State command returns package/device data plus a persistent local counter.
The Battery command reads `BatteryManager` once per command and returns
availability, capacity percentage, and charging state. It does not install a
battery receiver or background monitor. Both handlers execute synchronously on
the Worker's serialized callback lane.

The application ID remains:

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

## Device-local capability probe

The App owns the probe port and passes `18084` to the reusable HTTP module. It
listens only on the device loopback address. This path does not require Redis,
Python Kernel, Java Server, or an Adapter. Install and open the App, then
forward a host loopback port to the device:

```powershell
adb forward tcp:18084 tcp:18084
curl.exe http://127.0.0.1:18084/health
curl.exe http://127.0.0.1:18084/events
curl.exe -X POST `
  -H "Content-Type: application/json" `
  -d "{}" `
  http://127.0.0.1:18084/events/android.state.read:call
curl.exe -X POST `
  -H "Content-Type: application/json" `
  -d "{}" `
  http://127.0.0.1:18084/events/android.battery.read:call
curl.exe -X POST `
  -H "Content-Type: application/json" `
  -d '{"algorithm":"MD5","value":"hello"}' `
  http://127.0.0.1:18084/events/android.string.digest:call
```

These calls update the same processed-command count and last-event observation
shown by the App because HTTP and Worker delivery share one capability
instance. They prove local capability execution only. Remove the forwarding
rule when finished:

```powershell
adb forward --remove tcp:18084
```

## Real device run

Redis must be available at `redis://localhost:6379/15`. Start the Python
Kernel Task API:

```powershell
python -m kernel_design.runtime_server `
  --config xa-android/worker-demo/kernel-config.json
```

Start the shared Lab profile:

```powershell
.\gradlew.bat :server_jvm:bootRun `
  --args="--spring.profiles.active=scenario-workers"
```

The profile initializes the advisory `android-demo-workers` catalog and the
long-lived Group Task, but does not construct the Android Worker. Route the
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

Wait for `RUNNING`, then call all three capabilities through the public
WorkerGroup route:

```powershell
.\gradlew.bat :xa-android:worker-demo:runDemoRpc
```

The driver sends three standard Items with `allocationRule: {}` and prints the
State, Battery, and parameterized string digest results. It does not create
or close a Task, expose the Profile-owned Task ID, or select a Worker ID.

Because an empty allocation rule may select any schedulable Worker in the
Group, this acceptance assumes `android-demo-workers` contains only active Lab
devices. Persisted but disconnected candidates can legitimately leave a call
at `202 pending`; the driver does not retry or silently switch to a targeted
Worker.

## Verification

```powershell
.\gradlew.bat :xa-android:worker-demo:testDebugUnitTest
.\gradlew.bat :xa-android:worker-demo:assembleDebug
.\gradlew.bat :xa-android:capability-http:testDebugUnitTest
.\gradlew.bat :xa-android:capability-http:assembleDebug
python -m unittest discover `
  -s xa-android/worker-demo/host `
  -p "test_*.py"
```

`minSdk 24` is the build baseline. Hosted CI runs the Debug and host-driver
proofs without an Emulator. The documented Android 13 device flow remains the
manual real-device acceptance.
