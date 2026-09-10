# XA Mass Server Runtime distribution

This module owns the sole production main, Boot JAR and publishable Server
runtime archive. `com.xa.mass.server.XaMassServerApplication` imports
`XaMassServerConfiguration` and the profile-scoped SMS configuration into one
context. `server_jvm` and SMS Backend are libraries; neither produces a Boot JAR.
Distribution contains no application services or resource operations.

The JAR packages Server, its production dependencies, the SMS business module
and the independent SMS frontend at a separate classpath location. The archive
also packages the compiled platform frontend. It does not
package the repository-local Scenario Worker Host. It also generates and
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
`sms-reception` enables the product at `/sms` and `/api/v1/sms/*`, with one
Server on 18390 and Adapter on 18393. It requires an explicit Redis scope.
The [product launcher](../../products/sms-reception/README.md#启动与交付) supplies
a finite test scope and starts the separate SIM Host on 18394. Without that
profile there are no SMS routes, static assets, Groups or background jobs.
The root platform frontend remains independent of product enablement.

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
wheel or virtual environment. The standalone Scenario Lab remains available
only from the source checkout through `run_local_runtime.py` or
`:scenario_workers_jvm:runScenarioWorkers`.

The archive verifier requires the diagnostic JSON and checks its version and
full Git commit against `manifest.json`, plus the exact Server, Adapter and
Worker Core owner order. It also requires the OpenAPI 3.1 snapshot, rejects a
request-derived `servers` field or non-`/api/v1/**` paths, and checks the stable
four-Tag navigation order.
