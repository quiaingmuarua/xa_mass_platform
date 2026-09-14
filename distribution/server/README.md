# XA Mass Server Runtime distribution

This module owns Runtime ZIP delivery, frontend builds, diagnostic dictionaries
and archive verification. It consumes the sole Boot JAR produced by
[Spring Server composition](../../spring_server_jvm/README.md). It has no Java
entrypoint, Spring composition, application services or resource operations.

The JAR contains the complete Server library and both business Scenario libraries.
The executable's `preview` profile enables both on one platform. The archive
packages the unified Console for Runtime, Reference, SMS and Messages, plus the
current-build diagnostic dictionary. It excludes the independent Worker Simulator.
Redis remains external; the JAR embeds no separate business frontend.
The compiled frontend also carries the committed, Server-verified OpenAPI
snapshot at `frontend/dist/reference/openapi.json`; unlike the diagnostic
dictionary, that snapshot is generated explicitly and tracked in source.

The projection is produced from an explicit three-owner allowlist rather than
a repository-wide scan:

```powershell
.\gradlew.bat :distribution:server:generatePlatformDiagnosticCodes
.\gradlew.bat :distribution:server:verifyPlatformDiagnosticCodes
```

The build reads compiled `ServerErrorCode`,
`WorkerDeliveryAdapterErrorCode`, and `WorkerErrorCode` enums through an
isolated ClassLoader. It writes only under
`distribution/server/build/generated/reference` and adds the JSON to
`frontend/dist/reference` during local or archive assembly. Scenario Workers,
Integrations, Android Capabilities, Frontend/Distribution-local exceptions and
downstream Extensions are deliberately outside this lookup. Kernel currently
has no unified numeric ErrorCode catalog and is not represented. The generated
file is not committed and is not a compatibility contract.

Build an explicit release version:

```powershell
.\gradlew.bat :distribution:server:distZip "-PxaMassVersion=0.5.0"
```

The archive is written under `distribution/server/build/distributions`. After
extracting it on a machine with Java 21, run from the extracted Runtime root:

```powershell
java -jar .\lib\xa-mass-server-jvm-0.5.0.jar `
  --spring.profiles.active=scenario-workers `
  --spring.web.resources.static-locations=file:frontend/dist/ `
  --xa.mass.redis.url=redis://127.0.0.1:6379/15
```

Select the built-in clean `agentforge` deployment preset explicitly:

```powershell
java -jar .\lib\xa-mass-server-jvm-0.5.0.jar `
  --spring.profiles.active=agentforge `
  --spring.web.resources.static-locations=file:frontend/dist/
```

Only Profiles listed in the schema-v5 Runtime manifest are supported. The
`agentforge` preset uses Server/Adapter ports 18182/18183, Redis scope
`profile_agentforge`, Adapter ID `agentforge-websocket`, and no configured
WorkerGroup. `scenario-workers` retains the 18082/18083 Lab assembly.
`preview` enables both business scenarios on one mixed-country `demo-sim` Group.
The executable supplies complete shared event declarations; scenarios consume
them through existing Group registration. One context retains one Pacer, Matching
catalog and set of Redis Owners.

The [Scenario Preview](../scenario-preview/README.md) owns the independent Host
process and preview ZIP. It always enables SMS and Messages. The executable owns
all four canonical application configurations. Delivery copies the preview profile
without another maintained default. Archive verification requires the host resources
and rejects deployment or test configuration in nested platform/Scenario libraries.
Ordinary platform profiles enable neither business scenario.

The executable owns finite Console page forwards in platform and preview instances.
Unknown APIs/assets remain errors. Catalog observations control navigation and
unavailable states; shared assets do not enable scenarios. Composition tests live
in `spring_server_jvm`, while archive verification stays here.

Endpoint overrides use `xa.mass.worker-endpoints`. Each configured transport
type must name an explicit default in `defaults`; `endpoints` supplies the URI
directory. For example, the `agentforge` profile declares
`defaults.WEBSOCKET=agentforge-websocket`. Multiple WebSocket or Socket Endpoints
may share a type. A changed default affects new Bindings only; existing Workers
keep their actual Endpoint, and connecting to a different Adapter is rejected.
Prepare request and response fields are unchanged.

This Scenario composition migration requires no data rebuild. Migration from
incompatible historical Worker Binding storage requires a stopped scope rebuild. There are no old
configuration aliases or runtime compatibility reads. Preserve configuration and
source Properties, stop all users of the explicitly selected scope, clear only
that scope with SCAN plus UNLINK, then register Groups, Workers and Tasks again.
The [Worker Redis contract](../../kernel_jvm/doc/runtime-redis/worker-runtime-redis-shape.md#scope-rebuild)
owns the precise cleanup and generated-file boundaries. All newly registered
Workers remain cold until valid network evidence activates them best-effort.

The Boot JAR leaves Redis lifecycle to the caller. The checked Profile selects
its fixed Java Pacer preset; the archive contains no Pacer policy file and
offers no per-field policy tuning. Server remains the sole Java Pacer lifecycle
owner. The archive contains no Scenario Worker implementation, Python runtime,
wheel or virtual environment. The independent Worker Simulator is available
from its install distribution, the Scenario Preview, or the checkout through
`run_local_runtime.py`. Direct Gradle launch is
`:worker_simulator_jvm:runWorkerSimulator --args="--config worker_simulator_jvm/config/lab.json"`;
all paths use the same Main and complete configuration.

The archive verifier requires the diagnostic JSON and checks its version and
full Git commit against `manifest.json`, plus the exact Server, Adapter and
Worker Core owner order. It also requires the OpenAPI 3.1 snapshot, rejects a
request-derived `servers` field or non-`/api/v1/**` paths, and checks the stable
four-Tag navigation order.
