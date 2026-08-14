# Android Capabilities

`:xa-android:capabilities` is an Android Library containing reusable
business Definitions for Android Workers. It depends on Worker Core but not on
the Android Worker assembly, Server, Kernel, Adapter, Redis, or a Host UI.

The current concrete `AndroidDemoCapabilities` collection provides:

- `android.state.read`: package/device information plus the persistent
  demo counter;
- `android.battery.read`: a one-shot battery capacity and charging
  snapshot;
- `android.string.digest`: a parameterized UTF-8 string digest accepting
  `{"algorithm":"MD5","value":"hello"}` and returning the lowercase
  digest.

The collection exposes immutable `WorkerEventDefinition` values and its own
small observer snapshot for the demo UI. It owns no Worker identity, Endpoint,
Task, network connection, thread, or lifecycle. Battery data is read only when
the command executes; no receiver or background monitor is installed.
The algorithm is an explicit Payload parameter with a fixed `MD5` allowlist;
arbitrary JCA algorithm names are rejected. MD5 is included only as a
deterministic Lab computation and is not presented as secure encryption.

This module deliberately has no generic capability SPI, catalog, reflection,
or dynamic registration mechanism. Future capability collections may expose
their own concrete composition entry when a real Host requires them.

## Verification

```text
./gradlew :xa-android:capabilities:testDebugUnitTest
./gradlew :xa-android:capabilities:assembleDebug
```
