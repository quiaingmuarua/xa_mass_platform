# XA Android

`xa-android/` is the XA Mass Android product domain. It owns reusable Android business
capabilities and installable Android Worker hosts; it does not own Worker
transport mechanics.

```text
:xa-android:capabilities
  -> concrete Android WorkerEventDefinition collections
  -> capability-owned state and Android data access

:xa-android:worker-demo
  -> installable demo Application and Activity
  -> AndroidWorker plus capability composition
  -> real-device WorkerGroup RPC driver

:transport:android-worker
  -> Worker identity, Register/Bind, networking, reconnect, and lifecycle
```

Capabilities depend on Worker Core, not on the Android Worker assembly. A
Host chooses capabilities and passes their immutable Definitions to
`AndroidWorker`. This keeps business handlers reusable from a normal App or a
future Xposed Host without moving Hook state into a generic capability layer.

No Xposed module is created in this slice. A future Worker-connected Xposed
Host may live under `xa-android/`, while analysis, Hook discovery, and unrelated
reverse-engineering projects remain outside this repository domain.

- [Android capabilities](capabilities/README.md)
- [Android Worker demo](worker-demo/README.md)
- [Android Worker transport](../transport/android-worker/README.md)
