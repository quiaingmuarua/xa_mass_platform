# Android WebSocket Worker Demo

This module is a real installable Android application that composes the
repository Worker layers without copying their state machines:

```text
OkHttpWorkerControlClient
  -> Register or restore long-lived workerId
  -> Endpoint Bind and complete Worker Properties refresh

AndroidOkHttpTextWebSocketClient
  -> WebSocketWorkerTransport
  -> android.demo.state.read
```

The application is an integration host, not an SDK. It does not depend on the
Server, Kernel, Scenario Worker, Netty Adapter, or Redis implementations.

## App Behavior

The first start creates and persists a `clientWorkerKey`, calls Register, and
persists the platform-issued canonical Worker ID in private
`SharedPreferences`. Later starts skip Register and call Bind with the same
Worker ID. Every Bind replaces the complete Worker Properties snapshot with
the current package, application version, Android SDK, manufacturer, and
model. The returned Endpoint URI is not persisted.

The Activity starts the Worker while visible and closes it from `onStop`.
There is no Service, WorkManager, heartbeat, offline command queue, or Android
background-lifetime claim. `TRANSPORT_CONNECTED` means that the WebSocket is
connected and the Worker Connection Bind frame was accepted by the client
network stack. It is not Kernel Worker-online truth.

The UI exposes a local persistent counter. `Increment` and `Reset` change that
state; a remote `android.demo.state.read` command returns the current counter
plus device and package information. Business output is intentionally allowed
to vary between devices.

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
