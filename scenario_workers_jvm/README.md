# XA Mass Scenario Workers JVM

`scenario_workers_jvm` is the finite Java 21 capability assembly used by the
checked-in `scenario-workers` Server profile. It is a root module rather than a
`transport/` module because its event definitions and resource declarations are
business scenario choices, not Worker Delivery mechanisms.

The module owns:

```text
PHONE_NUMBER
  -> phonenumber.e164
  -> phonenumber.country
  -> phonenumber.original-carrier

STRING_UTILS
  -> string.md5
  -> string.sha1
  -> string.base64.encode

explicit factory
  -> WorkerGroup upsert
  -> deterministic Worker registration
  -> explicit Worker Properties replacement
  -> explicit index.worker.region projection updates
  -> real WebSocket Worker startup
  -> bounded initial connection wait
  -> reverse-order close
```

Its complete public surface is:

```text
ScenarioWorkerBundleConfig
ScenarioWorkerBundle
ScenarioWorkerBundles.phoneNumber(...)
ScenarioWorkerBundles.stringUtils(...)
```

Concrete bundle classes, capability definitions, Worker factories, and the
coded assembly exception remain module-local. This is not a plugin SPI:
configuration cannot supply a class name, handler, registry entry, or reflected
implementation.

The Server remains responsible for Spring configuration, Adapter construction,
validating the referenced Adapter, deriving the Worker WebSocket URI, and
sequencing Adapter startup before bundle startup. This module does not depend
on Spring, Server, the Netty Adapter, Redis, scores, Pacers, or HTTP
controllers. Kernel truth remains owned by `WorkerResourceCatalog`,
`WorkerRuntime`, and `WorkerPropertyIndexRuntime`; Scenario Workers only call
those existing owner operations. Worker registration or Properties update does
not auto-project the region snapshot into `index.worker.region`. A rejected or unavailable index
projection is logged and does not roll back Worker resource creation or
transport startup.

```text
./gradlew :scenario_workers_jvm:test
```
