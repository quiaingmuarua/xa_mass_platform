# Android Capabilities

`:xa-android:capabilities` is an Android Library containing reusable
business Definitions for Android Workers. It depends on Worker Core but not on
the Android Worker assembly, Server, Kernel, Adapter, Redis, or a Host UI.

The current concrete `AndroidDemoCapabilities` collection provides:

- `android.demo.state.read`: package/device information plus the persistent
  demo counter;
- `android.demo.battery.read`: a one-shot battery capacity and charging
  snapshot.

The collection exposes immutable `WorkerEventDefinition` values and its own
small observer snapshot for the demo UI. It owns no Worker identity, Endpoint,
Task, network connection, thread, or lifecycle. Battery data is read only when
the command executes; no receiver or background monitor is installed.

This module deliberately has no generic capability SPI, catalog, reflection,
or dynamic registration mechanism. Future capability collections may expose
their own concrete composition entry when a real Host requires them.

## Verification

```text
./gradlew :xa-android:capabilities:testDebugUnitTest
./gradlew :xa-android:capabilities:assembleDebug
```
