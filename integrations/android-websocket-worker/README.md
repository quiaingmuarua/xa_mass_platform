# Android WebSocket Worker Demo

This module is a real installable Android application that composes the
repository Worker layers without copying their state machines:

```text
MainActivity
  -> observes AndroidWorkerDemoHost

AndroidWorkerDemoApplication
  -> AndroidWorkerDemoHost
     -> AndroidWorker
     -> AndroidDemoStateCapability
        -> counter state
        -> android.demo.state.read Definition
```

The application is an integration host, not an SDK. It does not depend on the
Server, Kernel, Scenario Worker, Netty Adapter, or Redis implementations.

## App Behavior

The first start creates and persists a canonical UUID `clientWorkerKey`, calls
Register, and stores the platform-issued Worker ID in private
`SharedPreferences`. Existing non-empty client keys from earlier installs are
retained. Identity storage is owned by `transport:android-worker`; demo state
remains Integration-owned.

Every App Worker `start()` loads a complete device Properties snapshot and
performs Endpoint Bind, even when the long-lived Worker ID already exists. The
returned URI belongs only to that local run and is not persisted. Temporary
WebSocket disconnects reconnect to the same bound URI without another Register
or Bind. If the bounded reconnect budget is exhausted, Core creates no
replacement Transport: the Worker enters `STOPPED` and waits for the host to
call `start()` again. A later explicit or process start restores the same
Worker ID, skips Register, and performs Bind again before connecting.

`AndroidWorker` has no Activity, Service, Application subclass, UI, or demo
state dependency. It accepts a Properties function and Definitions, then owns
identity recovery and concrete Android Client assembly while Core owns
Register/Bind sequencing and WebSocket Worker composition. An Android host can
retain it from an Application, Service, or another process owner without
changing Worker execution.

This demo chooses `AndroidWorkerDemoApplication` as that owner and starts the
Worker when the application process is created. `MainActivity` only subscribes
to snapshots while visible and invokes explicit Connect/Disconnect commands;
leaving the Activity does not implicitly stop the Worker. Android can still
kill the process in the background because this demo installs no Service or
WorkManager and makes no background-lifetime guarantee.

The Application owns one `AndroidWorkerHostResources` configured for one
Worker and four concurrent Commands. It contains shared OkHttp infrastructure,
one network `HandlerThread`, a Control executor, and a zero-buffer Command
pool. The Demo Host borrows only the Control `Executor` to keep Application and
UI calls non-blocking; `AndroidWorker` borrows the complete resource bundle.
The Application closes the Worker first and Host Resources last. Core creates
no background thread of its own.

The Demo capability may execute concurrently up to the configured capacity.
Its state updates are thread-safe. When all four execution slots are occupied,
the Worker returns an immediate `1500` for the additional Command rather than
queueing it.

The UI reports only the Worker run state, `RUNNING` or `STOPPED`. WebSocket
connection and reconnect state remain private to the Android Client and are not
treated as Worker lifecycle or Kernel Worker-online truth. A real command/result
round trip is the end-to-end connection proof.

The example-specific counter belongs to `AndroidDemoStateCapability`, together
with its Definition, Handler, processed count, and last-event observation. It
is not Android Worker state. `Increment` and `Reset` change that capability; a
remote `android.demo.state.read` command returns the current counter, package
version, and device information. The capability owns no Worker ID, Endpoint,
Activity, or control-client dependency. Business output is intentionally
allowed to vary between devices.

The App excludes Android Worker Identity preferences from Auto Backup, so a
device restore cannot silently duplicate a long-lived Worker identity. A real
host must make that backup policy decision explicitly.

If the Server Identity registry is reset while the App still has a Worker ID,
Bind fails visibly and the App does not replace its identity. Clear the App's
data explicitly before issuing a new identity:

```powershell
adb shell pm clear com.xa.mass.integration.androidworker
```

## Real Device Run

Redis must be available at `redis://localhost:6379/15`. Start the Python Kernel
Task API:

```powershell
python -m kernel_design.runtime_server `
  --config integrations/android-websocket-worker/kernel-config.json
```

Start the Java Runtime API with the dedicated profile:

```powershell
.\gradlew.bat :server_jvm:bootRun `
  --args="--spring.profiles.active=android-worker-demo"
```

The profile initializes only `android-demo-workers` and the
`android-demo-websocket` Adapter on port `18085`; it starts no JVM Scenario
Workers. Route both required device-local ports to the Windows host:

```powershell
adb reverse tcp:18082 tcp:18082
adb reverse tcp:18085 tcp:18085
```

Install and open the App:

```powershell
.\gradlew.bat :integrations:android-websocket-worker:installDebug
adb shell am start -n `
  com.xa.mass.integration.androidworker/.MainActivity
```

Wait for `RUNNING` and a displayed Worker ID, then run one real Task RPC:

```powershell
.\gradlew.bat :integrations:android-websocket-worker:runDemoRpc `
  -PworkerId=<canonical-worker-uuid>
```

The Gradle task launches the small standard-library Python host driver. The
driver creates and approves an ITEM_DRIVEN Task, targets the displayed Worker
ID, prints the decoded Android result, and closes the Task in `finally`. Task
control never runs inside the Worker App.

## Verification

```powershell
.\gradlew.bat :integrations:android-websocket-worker:testDebugUnitTest
.\gradlew.bat :integrations:android-websocket-worker:assembleDebug
.\gradlew.bat :integrations:android-websocket-worker:assembleDebugAndroidTest
python -m unittest discover `
  -s integrations/android-websocket-worker/host `
  -p "test_*.py"
git diff --check
```

`minSdk 24` is the build baseline. The checked real-device proof currently
targets an Android 13 / API 33 device; a successful DEX build alone is not an
API 24 runtime proof.
