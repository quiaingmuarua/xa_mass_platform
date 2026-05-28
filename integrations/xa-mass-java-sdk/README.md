# xa-mass-java-sdk

Status: JSDK-1 skeleton.

`xa-mass-java-sdk` is the pure external Java client for a running
`xa-mass-server`.

It is intentionally separate from `xa-mass-sdk`, which is the embedded runtime
composition SDK.

## Scope

Current implemented surface:

- `MassPlatform.builder()`
- base URL normalization
- API key or bearer auth header injection
- JDK `HttpClient` based HTTP core
- Jackson-based `ApiResponse<T>` envelope handling
- typed client exceptions
- task shell, item ingest, command, result window, and archive clients

Not implemented in this skeleton:

- worker topology client
- managed polling worker session
- realtime worker client

Those are tracked in [../../doc/JAVA_EXTERNAL_SDK_ROADMAP.md](../../doc/JAVA_EXTERNAL_SDK_ROADMAP.md).

## Example

```java
MassPlatform mass = MassPlatform.builder()
        .baseUrl("http://localhost:8088")
        .apiKey("mass_sk_xxx")
        .build();
```

## Boundary

Production code in this module must not depend on engine, server, embedded SDK,
worker runtime, or transport implementation modules.
