# XA Mass Worker

Status: one Android-compatible Java Worker library.

The Worker boundary is intentionally small:

```text
:worker_delivery_contract_jvm
  -> Worker Delivery DTOs, Jsons, strict codec

:worker:okhttp-worker
  -> serial command execution
  -> WorkerEventHandler contract
  -> Polling, WebSocket, and line-oriented Socket transports
```

`worker:okhttp-worker` is a Java 11 library compiled with a JDK 21 toolchain.
It is not an application, CLI, packaged Android component, or lifecycle owner.
JVM and Android hosts may depend on the same module and decide how to construct
handlers, start threads, and bind process or Android component lifecycle.

The library contains no default business handlers. Tests use temporary local
handlers to prove command processing and transport convergence without making
business output part of the Worker framework contract.

Dependency direction:

```text
host application
  -> worker:okhttp-worker
       -> worker_delivery_contract_jvm
       -> OkHttp implementation
```

The Worker library cannot access Server internals, Kernel owners, Redis,
scheduling scores, Pacers, or TaskType. OkHttp is an implementation detail and
does not appear in public method signatures.

See [OkHttp Worker](okhttp-worker/README.md).

## Verification

```text
./gradlew :worker_delivery_contract_jvm:test
./gradlew :worker:okhttp-worker:test
```
