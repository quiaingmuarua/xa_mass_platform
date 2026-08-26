# XA Mass Server Runtime distribution

This module owns the publishable Server runtime archive. It packages the Java
Server, the production-only Python Kernel Pacer wheel and pinned offline
dependency, the compiled frontend, default Pacer policy, and an optional
standalone Scenario Worker Host. It also generates and packages the
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
extracting it on a machine with Java 21 and Python 3.11 or newer:

```powershell
python .\bin\run-server.py --profile scenario-workers -- `
  --xa.mass.redis.url=redis://127.0.0.1:6379/15
```

The launcher defaults to `scenario-workers` and also accepts the built-in clean
`agentforge` deployment preset:

```powershell
python .\bin\run-server.py --profile agentforge
```

Only Profiles listed in the schema-v3 Runtime manifest are accepted. The
`agentforge` preset uses Server/Adapter ports 18182/18183, Redis scope
`profile_agentforge`, Adapter ID `agentforge-websocket`, and no configured
WorkerGroup. `scenario-workers` retains the 18082/18083 Lab assembly.

`run-server.py` creates only the unpacked
distribution's `.runtime/python-venv`, installs from the included wheelhouse
with `--no-index`, and leaves Redis lifecycle to the caller. It never starts
the packaged Worker Host. Start that optional local Lab separately:

```powershell
python .\bin\run-scenario-workers.py `
  --runtime-api-base-url=http://127.0.0.1:18082 `
  --sandbox-root=D:\proof\data\scenario-workers
```

Stopping the Worker Host leaves Server, Adapter and Pacer available. Set
`XA_MASS_JAVA_EXECUTABLE` to replace the `java` command. Set
`XA_MASS_KERNEL_PACER_CONFIG` to an absolute policy JSON when the consuming
repository needs different Pacer intervals; the Java Server remains the only
Pacer process supervisor. Spring Boot arguments must follow `--`, and the
launcher rejects forwarded `spring.profiles.active`, Pacer-process, and
frontend-path overrides that it owns itself.

The archive verifier requires the diagnostic JSON and checks its version and
full Git commit against `manifest.json`, plus the exact Server, Adapter and
Worker Core owner order. It also requires the OpenAPI 3.1 snapshot, rejects a
request-derived `servers` field or non-`/api/v1/**` paths, and checks the stable
four-Tag navigation order.
