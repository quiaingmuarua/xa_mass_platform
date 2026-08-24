# XA Mass Server Runtime distribution

This module owns the publishable Server runtime archive. It packages the Java
Server, the production-only Python Kernel Pacer wheel and pinned offline
dependency, the compiled frontend, default Pacer policy, and a Python launcher.
Redis remains external.

Build an explicit release version:

```powershell
.\gradlew.bat :distribution:server:distZip "-PxaMassVersion=0.1.0"
```

The archive is written under `distribution/server/build/distributions`. After
extracting it on a machine with Java 21 and Python 3.11.3 through 3.13:

```powershell
python .\bin\run-server.py -- --xa.mass.redis.url=redis://127.0.0.1:6379/15
```

The launcher always selects `scenario-workers`. It creates only the unpacked
distribution's `.runtime/python-venv`, installs from the included wheelhouse
with `--no-index`, and leaves Redis lifecycle to the caller. Set
`XA_MASS_JAVA_EXECUTABLE` to replace the `java` command. Set
`XA_MASS_KERNEL_PACER_CONFIG` to an absolute policy JSON when the consuming
repository needs different Pacer intervals; the Java Server remains the only
Pacer process supervisor. Spring Boot arguments must follow `--`, and the
launcher rejects profile, Pacer-process, and frontend-path overrides that it
owns itself.
