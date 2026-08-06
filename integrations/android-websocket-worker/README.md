# Android WebSocket Worker Demo

This module is a real installable Android application that composes the
repository Worker layers without copying their state machines:

```text
MainActivity
  -> observes AndroidWorkerDemoHost

AndroidWorkerDemoApplication
  -> AndroidWorkerDemoHost
     -> AndroidWebSocketWorkerPlugin
        -> Identity Store
        -> Endpoint Cache Store
        -> Register / Bind control client
        -> Android WebSocket Client
        -> WebSocketWorkerTransport
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
retained. Identity storage contains only the WorkerGroup, client key, and
Worker ID; demo state and endpoint routing are stored separately.

After Bind, the App caches the returned Endpoint URI together with the Worker
ID and a canonical SHA-256 of the submitted Worker Properties. A later start
with the same identity and Properties skips both Register and Bind and connects
directly to the cached URI. Missing, damaged, mismatched, or stale-Properties
cache entries cause a new Bind, which replaces the complete device Properties
snapshot. Endpoint cache failure never replaces the long-lived identity.

Cached endpoint recovery is intentionally local to this Integration. Three
consecutive connections that fail before remaining open for one request-timeout
window trigger at most one background Bind per App start. The existing client
keeps reconnecting while that Bind runs. A changed URI replaces the Transport;
an unchanged URI only refreshes the cache. Bind failure keeps the prior route
and does not form a Bind loop. This heuristic is not a Worker Core contract or
an authentication mechanism.

`AndroidWebSocketWorkerPlugin` has no Activity, Service, Application, UI, or
demo-state dependency. It accepts Worker Properties and Definitions, then owns
only identity recovery, Register/Bind decisions, endpoint recovery, and the
WebSocket Worker Transport. An Android host can explicitly call its
`start/stop/close` lifecycle from an Application, Service, or another process
owner without changing Worker execution.

This demo chooses `AndroidWorkerDemoApplication` as that owner and starts the
Plugin when the application process is created. `MainActivity` only subscribes
to snapshots while visible and invokes explicit Connect/Disconnect commands;
leaving the Activity does not implicitly stop the Worker. Android can still
kill the process in the background because this demo installs no Service or
WorkManager and makes no background-lifetime guarantee.

`TRANSPORT_CONNECTED` means that the WebSocket is connected and the Worker
Connection Bind frame was accepted by the client network stack. It is not
Kernel Worker-online truth.

The example-specific counter belongs to `AndroidDemoStateCapability`, together
with its Definition, Handler, processed count, and last-event observation. It
is not Worker Plugin state. `Increment` and `Reset` change that capability; a
remote `android.demo.state.read` command returns the current counter, package
version, and device information. The capability owns no Worker ID, Endpoint,
Activity, or control-client dependency. Business output is intentionally
allowed to vary between devices.

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

Wait for `TRANSPORT_CONNECTED`, copy the displayed Worker ID, and run one real
Task RPC:

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
