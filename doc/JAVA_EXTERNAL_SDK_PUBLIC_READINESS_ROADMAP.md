# Java External SDK Public Readiness Roadmap

Status: proposed follow-up roadmap for making
[`../integrations/xa-mass-java-sdk`](../integrations/xa-mass-java-sdk) a real
external JVM SDK.

The completed [`JAVA_EXTERNAL_SDK_ROADMAP.md`](./JAVA_EXTERNAL_SDK_ROADMAP.md)
created a pure remote Java client and polling worker session. This roadmap is
the next readiness track: it hardens the artifact, API surface, dependency
guards, documentation, and release shape so external callers can depend on it
without pulling in the platform builder.

## Current Facts

- `xa-mass-sdk` is an embedded runtime composition SDK. It can assemble engine,
  transports, and server/runtime surfaces, so it is not the right artifact for
  repo-external callers.
- `integrations/xa-mass-java-sdk` is the correct external SDK owner. It talks
  to a running `xa-mass-server` through public HTTP worker/task APIs and owns
  managed polling sessions.
- current production dependencies are small: JDK `HttpClient` plus Jackson.
  The module does not currently depend on `xa-mass-sdk`, engine, server,
  worker-runtime, transport adapters, worker-pack, or `xa-mass-base`.
- current versioning follows the platform reactor as `0.0.1-SNAPSHOT`.
- current root POM metadata and module POM metadata are not publication-ready:
  release versioning, license metadata, source/javadoc artifacts,
  distribution target, signing, and compatibility policy are not yet defined.
- current architecture guard blocks several forbidden imports, but it does not
  yet block `xa-mass-base`, worker-pack, `com.xa.mass.command..`, or Maven
  dependency additions.
- current worker session implementation is polling-first. Realtime worker
  sessions and transport-independent event handler runtime are tracked in
  [`JAVA_EXTERNAL_SDK_REALTIME_PROTOCOL_ROADMAP.md`](./JAVA_EXTERNAL_SDK_REALTIME_PROTOCOL_ROADMAP.md).

## Owner Review

Public external Java SDK readiness belongs to `integrations/xa-mass-java-sdk`.

The SDK may own:

- public JVM client API ergonomics.
- typed remote task and worker clients.
- worker session runtime for external worker processes.
- transport-independent event handler runtime, once the realtime roadmap
  reaches that slice.
- release metadata, compatibility policy, documentation, and samples for the
  external Java artifact.

The SDK must not own:

- embedded runtime assembly.
- engine scheduling, worker matching, result convergence, terminal policy, or
  server bootstrap.
- transport adapter server implementations.
- worker-pack sample/fault behavior.
- Android/device host runtime inside the JVM artifact.

`xa-mass-java-sdk` should be a dependency of external applications, samples,
and worker-pack consumers. It should not become a dependency of server,
engine, transport runtime, or embedded SDK production code.

## Hard Rules

1. Do not merge `xa-mass-java-sdk` into `xa-mass-sdk`.
2. Do not make `xa-mass-java-sdk` depend on `xa-mass-sdk`, `xa-mass-base`,
   worker-pack, engine, server, worker-runtime, or transport adapter
   implementation modules.
3. Do not expose embedded runtime builders, server startup, or platform
   composition through the external SDK.
4. Public client methods must map to documented public HTTP routes or a
   documented public realtime protocol frame.
5. Raw HTTP escape hatches must not become the primary public API.
6. Publication readiness must include versioning, compatibility, dependency,
   source/javadoc, license, and release verification decisions before any
   external registry publish.
7. JVM SDK and Android/device host support are separate artifacts unless a
   later owner decision proves a shared artifact is viable.

## Do Not Start With

Do not start by publishing the current reactor artifact externally.

The current SDK is useful internally and for repo samples, but publication
turns API shape, package names, DTOs, transitive dependencies, Java baseline,
and error semantics into compatibility commitments. First classify and harden
the public surface.

## PSDK-0: Public Surface Inventory

Scope:

- inventory every public type under `com.xa.mass.client..`.
- classify public types as:
  - stable external API.
  - advanced diagnostic escape hatch.
  - internal candidate that should move or be hidden before publication.
  - test/sample-only concept accidentally exposed.
- classify `MassPlatform.http()` and `MassHttpClient` explicitly.
- classify Java baseline and caller target:
  - JVM server/desktop process.
  - Android/device host follow-up.
- classify public DTOs against documented HTTP routes.
- classify exception types and what fields/messages are stable.
- classify current README examples against public API promises.

Out of scope:

- implementation.
- artifact publication.
- realtime WebSocket API.

Acceptance:

- an inventory lists all public packages and types.
- every public type has a stability classification.
- raw HTTP access is either marked advanced/unstable or planned for
  restriction before publication.
- Java baseline and supported runtime target are explicitly recorded.
- any public type that mirrors internal server/runtime vocabulary is called
  out before it becomes a compatibility promise.

Verification candidates:

```powershell
rg -n "^public " integrations/xa-mass-java-sdk/src/main/java
rg -n "public MassHttpClient http|public Builder objectMapper|public Builder httpClient" integrations/xa-mass-java-sdk/src/main/java
```

## PSDK-1: Dependency And Boundary Guards

Scope:

- extend `JavaExternalSdkArchitectureGuardTest` to block production imports
  from:
  - `com.xa.mass.command..`
  - `com.xa.mass.base..` if that package appears later.
  - engine, server, embedded SDK, worker-runtime, and transport runtime
    packages.
- add a Maven dependency guard that fails if production dependencies include:
  - `com.xa.mass:xa-mass-sdk`
  - `com.xa.mass:xa-mass-base`
  - `com.xa.mass:xa-mass-worker-pack`
  - engine, server, worker-runtime, or transport adapter artifacts.
- keep Spring Boot dependencies test-scoped only.
- add a dependency tree verification command to the roadmap and README.

Out of scope:

- adding new SDK features.
- moving worker-pack.

Acceptance:

- forbidden source imports fail in SDK tests.
- forbidden Maven dependencies fail during `validate` or a focused guard test.
- production dependency tree remains limited to approved third-party
  libraries.
- guard covers both direct imports and accidental POM additions.

Verification candidates:

```powershell
mvn -pl integrations/xa-mass-java-sdk validate test
mvn -pl integrations/xa-mass-java-sdk dependency:tree
```

## PSDK-2: Public API Hardening

Scope:

- decide the stability of:
  - `MassPlatform`.
  - `TaskClient`.
  - `WorkerClient`.
  - `WorkerSessions`.
  - `PollingWorkerSession`.
  - `MassPayload`.
  - exception types.
  - raw HTTP access.
- add or update README wording that separates stable typed APIs from advanced
  diagnostics.
- normalize naming around event handler runtime so public SDK wording does not
  drift back to embedded `command` ownership.
- define binary/source compatibility expectations for public packages.
- define deprecation policy for public APIs.
- define whether package-private/internal implementation packages are needed
  before publication.

Out of scope:

- transport realtime implementation.
- public Maven publication.

Acceptance:

- stable API entry points are named and documented.
- raw HTTP access is not presented as the normal integration path.
- public exceptions preserve safe request identity without exposing secrets.
- API compatibility policy exists before any non-SNAPSHOT external publish.

Verification candidates:

```powershell
mvn -pl integrations/xa-mass-java-sdk test
rg -n "http\\(\\)|MassHttpClient|@Deprecated|deprecated|internal" integrations/xa-mass-java-sdk/src/main/java integrations/xa-mass-java-sdk/README.md
```

## PSDK-3: Worker SDK Ergonomics

Scope:

- align with
  [`JAVA_EXTERNAL_SDK_REALTIME_PROTOCOL_ROADMAP.md`](./JAVA_EXTERNAL_SDK_REALTIME_PROTOCOL_ROADMAP.md)
  for:
  - transport-independent event handler runtime.
  - result sink/queue.
  - lifecycle listeners.
  - active result reporting.
- keep polling as the stable public worker session until realtime protocol
  contract exists.
- ensure polling worker examples use the same handler concepts that realtime
  sessions will use later.

Out of scope:

- implementing WebSocket before protocol contract.
- making Android/device runtime part of this JVM SDK.

Acceptance:

- public worker SDK concepts are transport-neutral where they should be.
- polling session does not accumulate handler/result logic that WebSocket must
  duplicate later.
- SDK docs explain which features are stable now and which are planned.

Verification candidates:

```powershell
mvn -pl integrations/xa-mass-java-sdk test
mvn -pl xa-mass-testing -Dtest=JavaPollingWorkerBlackBoxIntegrationTest test
```

## PSDK-4: External Documentation And Samples

Scope:

- provide external-caller documentation for:
  - task producer.
  - polling worker.
  - worker topology setup.
  - result reading.
  - error handling.
  - timeouts and lifecycle callbacks.
- add a migration note that explains why external callers should use
  `xa-mass-java-sdk` instead of `xa-mass-sdk`.
- document Java baseline, supported runtime target, dependency expectations,
  and auth modes.
- ensure samples build from a clean checkout with clear commands.
- keep realtime docs marked planned until protocol contract and implementation
  land.

Out of scope:

- generated docs site.
- Android/device SDK docs.

Acceptance:

- README and sample docs are usable without knowing platform internals.
- examples do not require embedding server/engine runtime.
- docs distinguish stable current APIs from proposed realtime/event-handler
  follow-ups.

Verification candidates:

```powershell
mvn -pl integrations/xa-mass-java-sdk test
mvn -q -f integrations/samples/java/worker-polling/pom.xml -DskipTests package
```

## PSDK-5: Release And Publication Readiness

Scope:

- decide release target:
  - internal registry.
  - GitHub Packages.
  - Maven Central.
  - local-only reactor artifact for now.
- define versioning policy and compatibility policy.
- add release metadata needed by the selected target:
  - license.
  - SCM.
  - developers/organization if required.
  - source and javadoc artifacts.
  - signing/checksum policy if required.
- add local staging verification without pushing externally.
- define changelog/release notes format.

Out of scope:

- publishing to an external registry without explicit release approval.
- changing platform module versions globally unless the release plan requires
  it and is approved.

Acceptance:

- `xa-mass-java-sdk` can produce publication-ready artifacts locally.
- dependency tree and metadata are reviewable before any publish.
- release decision states whether the first external target is public or
  private.
- no external publish happens as a side effect of normal test/build commands.

Verification candidates:

```powershell
mvn -pl integrations/xa-mass-java-sdk -DskipTests package
mvn -pl integrations/xa-mass-java-sdk -DskipTests source:jar javadoc:jar
```

## PSDK-6: Android And Device Host Decision

Scope:

- decide whether AgentForge-style Android/device worker host support needs a
  separate artifact.
- decide what can be shared with the JVM SDK:
  - protocol docs.
  - event handler concepts.
  - generated or copied DTOs.
- decide what must stay separate:
  - Android threading.
  - Android lifecycle.
  - OkHttp or platform-specific WebSocket client.
  - app/device permissions.

Out of scope:

- adding Android dependencies to `xa-mass-java-sdk`.
- publishing Android artifacts before the JVM SDK boundary is stable.

Acceptance:

- Android/device host support has an owner decision before implementation.
- JVM SDK remains free of Android dependencies.

## Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| SDK becomes another platform builder | external callers inherit engine/server concepts | keep forbidden dependency and import guards |
| Publish freezes unstable API | compatibility debt | require PSDK-0 and PSDK-2 before any non-SNAPSHOT release |
| Raw HTTP core becomes public contract | users depend on paths/envelopes directly | classify raw HTTP as advanced/unstable or hide it |
| Java baseline excludes desired callers | adoption friction | record supported JVM target and split Android/device artifact |
| Release metadata is incomplete | publication fails or creates unusable artifacts | add PSDK-5 local staging verification |
| Worker SDK ergonomics duplicate per transport | WebSocket and polling diverge | route through realtime/event-handler roadmap before WebSocket implementation |

## Verification Matrix

| Phase | Verification |
| --- | --- |
| PSDK-0 | public surface inventory review |
| PSDK-1 | SDK guard tests, Maven dependency guard, dependency tree |
| PSDK-2 | SDK tests plus API documentation review |
| PSDK-3 | SDK tests plus Java polling black-box proof |
| PSDK-4 | sample package commands and README review |
| PSDK-5 | local source/javadoc/package staging, no external publish |
| PSDK-6 | decision record only |

## Non-Goals

- Do not replace or rename `xa-mass-sdk`.
- Do not merge external SDK behavior into `xa-mass-sdk`.
- Do not publish externally before publication readiness is reviewed and
  explicitly approved.
- Do not add engine, server, worker-runtime, transport adapter, worker-pack, or
  `xa-mass-base` production dependencies.
- Do not turn raw HTTP helpers into the primary public API.
- Do not include Android/device host runtime in the JVM SDK artifact.
