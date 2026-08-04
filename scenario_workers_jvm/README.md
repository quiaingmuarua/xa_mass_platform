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
  -> explicit Worker registration
  -> explicit Worker Properties replacement
  -> configured Property Index updates
  -> real WebSocket Worker startup
  -> bounded initial connection wait
  -> reverse-order close
```

Its complete public surface is:

```text
ScenarioWorkers.fromJson(...)
ScenarioWorkers.start()
ScenarioWorkers.close()
```

The JSON object is an ordered deployment manifest keyed by bundle ID. Each
bundle declares its finite built-in type, endpoint manager, final WebSocket
URI, WorkerGroup ID, and an explicit Worker list. Each Worker supplies its
complete `workerProperties` snapshot and optional `indexedPropertyUpdates`.
The module validates and parses that manifest; the Server treats it as opaque.

Concrete parsed configuration, bundle classes, capability definitions, Worker
factories, and the coded assembly exception remain module-local. This is not a
plugin SPI: configuration cannot supply a class name, handler, registry entry,
or reflected implementation. Event codes and handlers remain code-owned by
the selected finite bundle type.

The Server remains responsible for Spring configuration, Adapter construction,
and sequencing Adapter startup before the aggregate `ScenarioWorkers` handle.
It does not inspect bundle or Worker settings. This module does not depend on
Spring, Server, the Netty Adapter, Redis, scores, Pacers, or HTTP controllers.
Kernel truth remains owned by `WorkerResourceCatalog`,
`WorkerRuntime`, and `WorkerPropertyIndexRuntime`; Scenario Workers only call
those existing owner operations. Registration and Properties replacement do
not auto-project any Index value. Rejected or unavailable configured Index
updates are logged and do not roll back Worker resource creation or transport
startup.

`close()` releases only local network and thread resources. It does not disable,
remove, or otherwise change Kernel Worker lifecycle truth.

```text
./gradlew :scenario_workers_jvm:test
```
