# XA Android

`xa-android/` is the XA Mass Android product domain. It owns reusable Android business
capabilities and installable Android Worker hosts; it does not own Worker
transport mechanics.

```text
:xa-android:capabilities
  -> concrete Android WorkerEventDefinition collections
  -> capability-owned state and Android data access

:xa-android:capability-http
  -> device-loopback HTTP for a finite extension Definition collection
  -> direct resolver, Handler, result, and Host-control verification

:xa-android:worker-demo
  -> installable demo Application and Activity
  -> AndroidWorker plus business and local-only Host Event composition
  -> API 33 Emulator acceptance and real-device WorkerGroup drivers

:transport:android-worker
  -> client-key persistence, Prepare, networking, reconnect, and lifecycle
```

Capabilities depend on Worker Core, not on the Android Worker assembly. A
Host chooses capabilities and passes their immutable Definitions to
`AndroidWorker` and, for Lab diagnostics, to the loopback-only Capability HTTP
probe. Both paths execute the same business Handler instances. The Demo may
append fixed lifecycle Definitions only to its local HTTP assembly; those
Events never enter AndroidWorker or WorkerGroup capability truth. This keeps business
handlers reusable from a normal App or a future Xposed Host without moving
Hook state into a generic capability layer.

Capability HTTP proves only that the installed App can execute its local
capability resolver and Handler. It owns no Worker identity or connection
truth and is not a substitute for the managed Task Call end-to-end proof. The
path-selected Emulator CI relates that local surface to real Worker identity,
Adapter route, Command/Result, Properties observation, explicit lifecycle,
Server terminal, and App process-restart behavior without UI automation.
Real-device acceptance remains separate for vendor systems, physical Battery
behavior, and background execution limits.

No Xposed module is created in this slice. A future Worker-connected Xposed
Host may live under `xa-android/`, while analysis, Hook discovery, and unrelated
reverse-engineering projects remain outside this repository domain.

- [Android capabilities](capabilities/README.md)
- [Android Capability HTTP](capability-http/README.md)
- [Android Worker demo](worker-demo/README.md)
- [Android Worker transport](../transport/android-worker/README.md)
