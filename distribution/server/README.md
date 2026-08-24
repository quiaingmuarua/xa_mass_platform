# XA Mass Server Runtime distribution

This module owns the publishable Server runtime archive. It packages the Java
Server, the production-only Python Kernel Pacer wheel and pinned offline
dependency, the compiled frontend, default Pacer policy, and an optional
standalone Scenario Worker Host. Redis remains external.

Build an explicit release version:

```powershell
.\gradlew.bat :distribution:server:distZip "-PxaMassVersion=0.3.0"
```

The archive is written under `distribution/server/build/distributions`. After
extracting it on a machine with Java 21 and Python 3.11.3 through 3.13:

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
