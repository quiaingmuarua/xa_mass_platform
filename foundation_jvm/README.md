# XA Mass JVM Foundation

Status: narrow Java 11 compatible error-classification contract.

The module exports only:

```text
ErrorCode
  stable integer code + default message

CodedRuntimeException
  required ErrorCode + owner.method operation + message/cause
```

Java forbids generic subclasses of `Throwable`, so the base exception stores
the non-generic `ErrorCode` interface. Each owner exception accepts only its
own enum and may override `errorCode()` with that covariant enum return type.

Owners define their own numeric ranges and error-code enums. A coded exception
contains only its error code, operation, message, cause, and the normal Java
stack trace. The operation uses a nonblank `owner.method` form. A null message
uses the code's default message.

Foundation does not define global codes, HTTP status, Worker outcomes, retry
policy, log levels, request IDs, attributes, or context maps. Exceptions are
failure classification and propagation, not telemetry envelopes. Complete
call context belongs to the log or tracing boundary. The module has no
framework, protocol, network, or logging dependency.

Logging uses JDK `System.Logger` directly in the owning module. Do not add a
Foundation logger facade or utility.

Verification:

```text
./gradlew :foundation_jvm:test
```
