# XA Mass Server Runtime distribution

This module owns Runtime and Scenario Preview ZIP delivery, frontend builds,
diagnostic dictionaries, Preview process assembly and archive verification.
It consumes the sole Boot JAR produced by
[Server Boot composition](../../server_boot_jvm/README.md). It has no Java
entrypoint, Spring composition, application services or platform Owner operations.

The Runtime archive packages the Boot JAR, unified Console and current-build
diagnostic dictionary. It excludes the independent Worker Simulator, Python
runtime, wheels and virtual environments. Redis remains external; the JAR
embeds no separate business frontend.
The compiled frontend also carries the committed, Server-verified OpenAPI
snapshot at `frontend/dist/reference/openapi.json`; unlike the diagnostic
dictionary, that snapshot is generated explicitly and tracked in source.

Boot owns [profiles and Project/Group/Scenario composition](../../server_boot_jvm/README.md#platform-and-preview)
and [page forwards and production configuration](../../server_boot_jvm/README.md#pages-and-configuration).
Distribution consumes that composition without creating platform Owners or
another maintained YAML default.

## Source launch

From the checkout root, build and start the complete local Lab:

```text
python run_local_runtime.py
```

The default is `scenario-workers`. The launcher builds and starts Server first,
waits for readiness, then starts the standalone Simulator against persistent
`data/scenario-workers`. Stopping Host closes its network resources without
stopping Server or deleting Workers, Groups or managed Tasks.

`python run_local_runtime.py --profile agentforge` builds the same frontend and
starts only Server with its configured Adapter; it does not build or start Host.
Unknown profiles are rejected. For business Preview, the root command delegates
in-process to the [sole Preview launcher](PREVIEW.md#source-launch):

```text
python -m pip install -r distribution/server/requirements-preview.txt
python run_local_runtime.py --profile preview
```

The [Simulator entry](../../worker_simulator_jvm/README.md#one-configuration-one-entry)
also supports standalone Host development; [Boot Run](../../server_boot_jvm/README.md#run)
starts only the Server composition.

## Diagnostic dictionary

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

## Runtime archive

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

Only profiles listed in the schema-v5 Runtime manifest are supported. Redis
lifecycle remains with the caller. The archive contains no Pacer policy file or
per-field policy tuning; the checked Boot profile selects its fixed preset.
Use the [Preview archive](PREVIEW.md#archive-delivery) when delivery must include
Host and the finite two-process launcher; it requires no platform release version.

Endpoint overrides retain the [Server Endpoint contract](../../server_jvm/README.md#endpoint-defaults-and-polling-evidence).
Scenario composition needs no data rebuild. Deployments crossing incompatible
historical Worker Binding storage must follow the stopped, exact-scope
[Worker rebuild procedure](../../kernel_jvm/doc/runtime-redis/worker-runtime-redis-shape.md#scope-rebuild);
there are no compatibility aliases or reads.

## Archive verification

The archive verifier requires the diagnostic JSON and checks its version and
full Git commit against `manifest.json`, plus the exact Server, Adapter and
Worker Core owner order. It also requires the OpenAPI 3.1 snapshot, rejects a
request-derived `servers` field or non-`/api/v1/**` paths, and checks the stable
four-Tag navigation order.
It requires the canonical Boot resources and rejects application configuration
in nested platform/Scenario libraries and all test configuration.
Preview adds [external-profile and packaged-launch checks](PREVIEW.md#archive-delivery).
