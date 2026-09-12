# XA Mass Server Runtime distribution

This module owns the sole production main, Boot JAR and publishable Server
runtime archive. `com.xa.mass.server.XaMassServerApplication` imports
`XaMassServerConfiguration` and profile-scoped SMS/Messages configurations into one
context. Server and both Backends are libraries; only distribution produces a Boot JAR.
Distribution contains no application services or resource operations.

The JAR packages Server, its production dependencies and both business libraries.
Preview deployment coordinates live in explicit distribution/launcher configuration;
product profiles enable business without injecting a second Adapter. The archive packages the compiled unified console
for Runtime, Reference, SMS and Messages pages; the JAR embeds no separate SMS frontend. It does not
package the repository-local Worker Simulator. It also generates and
packages the current-build Platform diagnostic code projection. Redis remains
external.
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
`sms-reception` and `message-campaigns` independently enable their business APIs,
Groups and jobs. `ProductWorkerConfiguration` supplies one mixed-country `demo-sim`
Group for every product combination and the enabled products' complete extension
event declarations. Each product consumes exactly that input via
the existing idempotent Group registration service; distribution owns no registration
workflow. One context retains one Pacer, Matching catalog and set of Redis Owners.

The [shared Preview](../product-preview/README.md) owns the common port/Redis/Adapter
configuration, separate Host process and sole product Preview ZIP. Its `--products`
selection covers SMS, Messages or both through the same deployment files. Products
retain their business profiles and acceptance oracles; Preview coordinates are
explicit external inputs, not embedded Boot defaults.

Distribution explicitly forwards the three SMS pages and Messages workspace,
metrics and single-segment campaign detail routes, including trailing slashes, to
one index in all four product combinations. Unknown APIs and assets are not
forwarded. Independent catalog observations control navigation and unavailable
states; the shared assets do not enable a product. Runtime and Reference remain
independent. `productCompositionIntegrationTest` proves all four combinations.

Endpoint overrides use `xa.mass.worker-endpoints`. Each configured transport
type must name an explicit default in `defaults`; `endpoints` supplies the URI
directory. For example, the `agentforge` profile declares
`defaults.WEBSOCKET=agentforge-websocket`. Multiple WebSocket or Socket Endpoints
may share a type. A changed default affects new Bindings only; existing Workers
keep their actual Endpoint, and connecting to a different Adapter is rejected.
Prepare request and response fields are unchanged.

This Worker Binding cutover requires a stopped scope rebuild. There are no old
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
from its install distribution, the Product Preview, or the checkout through
`run_local_runtime.py`. Direct Gradle launch is
`:worker_simulator_jvm:runWorkerSimulator --args="--config worker_simulator_jvm/config/lab.json"`;
all paths use the same Main and complete configuration.

The archive verifier requires the diagnostic JSON and checks its version and
full Git commit against `manifest.json`, plus the exact Server, Adapter and
Worker Core owner order. It also requires the OpenAPI 3.1 snapshot, rejects a
request-derived `servers` field or non-`/api/v1/**` paths, and checks the stable
four-Tag navigation order.
