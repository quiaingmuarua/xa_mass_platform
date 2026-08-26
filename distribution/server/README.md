# XA Mass Server Runtime distribution

This module owns the publishable Server runtime archive. It packages the Java
Server and its production Pacers, the compiled frontend, default Pacer policy,
and an optional standalone Scenario Worker Host. It also generates and packages the
current-build Platform diagnostic code projection. Redis remains external.
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
.\gradlew.bat :distribution:server:distZip "-PxaMassVersion=0.4.0"
```

The archive is written under `distribution/server/build/distributions`. After
extracting it on a machine with Java 21, run from the extracted Runtime root:

```powershell
java -jar .\lib\xa-mass-server-jvm-0.4.0.jar `
  --spring.profiles.active=scenario-workers `
  --xa.mass.kernel-pacer.config-path=config/pacer-default.json `
  --spring.web.resources.static-locations=file:frontend/dist/ `
  --xa.mass.redis.url=redis://127.0.0.1:6379/15
```

Select the built-in clean `agentforge` deployment preset explicitly:

```powershell
java -jar .\lib\xa-mass-server-jvm-0.4.0.jar `
  --spring.profiles.active=agentforge `
  --xa.mass.kernel-pacer.config-path=config/pacer-default.json `
  --spring.web.resources.static-locations=file:frontend/dist/
```

Only Profiles listed in the schema-v4 Runtime manifest are supported. The
`agentforge` preset uses Server/Adapter ports 18182/18183, Redis scope
`profile_agentforge`, Adapter ID `agentforge-websocket`, and no configured
WorkerGroup. `scenario-workers` retains the 18082/18083 Lab assembly.

The Boot JAR leaves Redis lifecycle to the caller and never starts the packaged
Worker Host. Start that optional local Lab separately with its generated script:

```powershell
.\scenario-workers\bin\xa-mass-scenario-workers.bat `
  --runtime-api-base-url=http://127.0.0.1:18082 `
  --sandbox-root=D:\proof\data\scenario-workers
```

Stopping the Worker Host leaves Server, Adapter and Pacers available. Set
`XA_MASS_KERNEL_PACER_CONFIG` or pass an explicit config path when the
deployment needs different Pacer intervals. Server remains the sole Java Pacer
lifecycle owner. The archive contains no Python runtime, wheel or virtual
environment.

The archive verifier requires the diagnostic JSON and checks its version and
full Git commit against `manifest.json`, plus the exact Server, Adapter and
Worker Core owner order. It also requires the OpenAPI 3.1 snapshot, rejects a
request-derived `servers` field or non-`/api/v1/**` paths, and checks the stable
four-Tag navigation order.
