# XA Android

`xa-android/` is the XA Mass Android product domain. It owns reusable Android business
capabilities and installable Android Worker hosts; it does not own Worker
transport mechanics.

```text
:xa-android:capabilities
  -> concrete Android WorkerEventDefinition collections
  -> capability-owned state and Android data access

:xa-android:capability-http
  -> device-loopback HTTP probe for TASK Definitions
  -> direct resolver, Handler, and logical-result verification

:xa-android:worker-demo
  -> installable demo Application and Activity
  -> AndroidWorker plus capability and local-probe composition
  -> real-device WorkerGroup RPC driver

:transport:android-worker
  -> Worker identity, Register/Bind, networking, reconnect, and lifecycle
```

Capabilities depend on Worker Core, not on the Android Worker assembly. A
Host chooses capabilities and passes their immutable Definitions to
`AndroidWorker` and, for Lab diagnostics, to the loopback-only Capability HTTP
probe. Both paths execute the same Handler instances. This keeps business
handlers reusable from a normal App or a future Xposed Host without moving
Hook state into a generic capability layer.

Capability HTTP proves only that the installed App can execute its local
capability resolver and Handler. It owns no Worker identity or connection
truth and is not a substitute for the WorkerGroup RPC end-to-end proof.

No Xposed module is created in this slice. A future Worker-connected Xposed
Host may live under `xa-android/`, while analysis, Hook discovery, and unrelated
reverse-engineering projects remain outside this repository domain.

- [Android capabilities](capabilities/README.md)
- [Android Capability HTTP](capability-http/README.md)
- [Android Worker demo](worker-demo/README.md)
- [Android Worker transport](../transport/android-worker/README.md)
