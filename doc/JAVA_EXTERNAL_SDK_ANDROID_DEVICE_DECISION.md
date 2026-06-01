# Java External SDK Android And Device Host Decision

Status: current PSDK-6 decision record for
[`JAVA_EXTERNAL_SDK_PUBLIC_READINESS_ROADMAP.md`](./JAVA_EXTERNAL_SDK_PUBLIC_READINESS_ROADMAP.md).

## Decision

Keep Android/device worker host support out of `xa-mass-java-sdk`.

`xa-mass-java-sdk` is the JVM SDK for server-side or desktop Java processes. It
may share protocol documents and event handler concepts with a future
Android/device host, but it must not depend on Android threading, Android
lifecycle APIs, OkHttp, app/device permissions, or device-specific packaging.

## Rationale

- current `xa-mass-java-sdk` uses JDK `HttpClient` and Java 21.
- AgentForge and Sekiro-style device clients are useful design references, but
  their platform lifecycle and networking dependencies are not suitable for
  this JVM artifact.
- keeping Android/device support separate prevents the external JVM SDK from
  becoming another broad platform runtime bundle.

## Allowed Sharing

- documented HTTP and realtime worker protocols.
- event handler runtime concepts.
- request/result DTO shapes after public contract review.
- sample behavior expectations and black-box parity scenarios.

## Separate Artifact Candidates

- `xa-mass-android-worker-sdk`
- `xa-mass-device-worker-sdk`

Those names are candidates only. A later roadmap should decide the exact owner,
artifact name, Java/Kotlin baseline, Android API level, network client, and
device lifecycle contract before implementation.

## Non-Goals

- Do not add Android, OkHttp, or device lifecycle dependencies to
  `xa-mass-java-sdk`.
- Do not treat AgentForge Android/WebSocket code as directly portable into the
  JVM SDK.
- Do not publish an Android/device artifact until the JVM SDK public boundary
  and worker protocol contracts are stable enough to share.
