# Transport Embedded Adapter Spec Assembly Convergence Roadmap

Status: implemented; current facts are mirrored in transport owner docs and
`TRANSPORT_EMBEDDED_ADAPTER_SPEC_ASSEMBLY_INVENTORY.md`.

## Summary

Embedded adapter runtime is now mostly shaped around:

- `EmbeddedAdapterRuntimeSpec` as startup intent;
- `EmbeddedTransportAdapterRuntimeFactory` as adapter runtime constructor;
- `EmbeddedAdapterStarter` as `spec.type -> factory` and
  `adapterId -> runtime` manager.

The remaining confusion is in SDK/starter assembly:

- `TransportRuntimeComposition` still builds polling/WebSocket/Socket specs
  directly.
- `TransportRuntimeComposition` still resolves registration descriptors by a
  separate `EmbeddedAdapterStarterDefaults.transportHintForType(...)` switch.
- `TransportRuntimeComposition` still behaves like a broad config/runtime
  helper: it snapshots typed config, resolves queues/stores, lazily owns an
  endpoint lease store, builds adapter specs, builds descriptors, and caches a
  registration resolver.
- `EmbeddedAdapterStarterDefaults.defaultFactories(...)` is the real fixed
  factory list, but it is package-private and not reusable by descriptor
  resolution.
- `TransportConfig` stores adapter-specific typed fields as if they were
  runtime assembly truth instead of builder-facing declaration sugar.
- WebSocket server factory sidecars are generated separately from specs, so a
  future spec append/replace path can orphan sidecars.

Target: **fixed built-in adapter registry + spec-driven embedded startup +
smaller SDK transport config/composition surface**.

No dynamic discovery. No `ServiceLoader`. No classpath scanning. No remote
adapter registration in this roadmap.

## Implementation Status

Implemented on 2026-06-29:

- `EmbeddedAdapterRuntimeFactoryRegistry` is the fixed built-in registry for
  `spec.type -> factory`, descriptor generation, and descriptor-only
  registration resolver construction.
- `EmbeddedAdapterStarterDefaults.transportHintForType(...)` was removed.
- `EmbeddedAdapterSpecAssembler` under `sdk/xa-mass-embedded-sdk` starter/config
  is the only production writer of built-in `EmbeddedAdapterRuntimeSpec`
  values.
- `TransportRuntimeComposition` delegates spec and WebSocket sidecar projection
  to the assembler and no longer owns registration resolver state or a lazy
  endpoint lease store.
- `MassApplication` creates backend runtime resources once during startup
  assembly, builds the descriptor-only resolver from the same registry/specs,
  and passes the same registry into `EmbeddedAdapterStarter`.

## Pre-Implementation Code Observations

- `EmbeddedAdapterRuntimeSpec` contains:
  `type / adapterId / dispatchQueueKey / resultQueueKey / options`.
- `EmbeddedTransportAdapterRuntimeFactory` exposes:
  `type()`, `descriptor(spec)`, and `create(spec, environment)`.
- `EmbeddedAdapterStarter` indexes factories by `type`, creates runtimes from
  specs, keeps `runtimeByAdapterId`, and owns runtime start/close.
- `EmbeddedAdapterStarterDefaults.defaultFactories(...)` constructs the built-in
  polling/WebSocket/Socket factory list, but it is package-private.
- `TransportRuntimeComposition.resolveEmbeddedAdapterRuntimeSpecs()` directly
  creates polling/WebSocket/Socket specs.
- `TransportRuntimeComposition.resolveRegistrationDescriptors()` calls
  `EmbeddedAdapterStarterDefaults.transportHintForType(spec.type())`.
- `TransportRuntimeComposition` has broad final fields for adapter config,
  queue/result/lease factories, capacity, timeouts, role, and transient runtime
  objects such as `registrationResolver` and `runtimeOwnedEndpointLeaseStore`.
- `TransportConfig.isEnabled()` and `TransportRuntimeComposition.isEnabled()`
  currently mean "user-enabled push/extra adapter declaration exists", not
  "the final spec list is non-empty". Polling is a default embedded adapter
  spec and must not make this public enabled flag true by itself.
- `MassApplication` already creates `EmbeddedAdapterStarter` and calls
  `create(resolveEmbeddedAdapterRuntimeSpecs())` through a generic path. The
  remaining runtime-type branching is in `TransportRuntimeComposition`, not in
  adapter startup.
- `TransportConvergenceArchitectureGuardTest` currently protects the old shape
  by requiring `TransportRuntimeComposition` to contain
  `new EmbeddedAdapterRuntimeSpec(`.
- `PollingAdapterRuntimeFactoryTest` does not exist yet, while WebSocket and
  Socket runtime factory tests already exist.

## Owner Review

`EmbeddedAdapterRuntimeSpec` belongs to transport runtime embedded support. It
is internal embedded runtime assembly truth, not default public SDK API.

Concrete adapter modules own:

- adapter runtime factory implementation;
- protocol-specific option parsing;
- protocol server creation;
- local session/channel registry;
- final-hop `selectedWorkerId -> local session/channel` send;
- result frame parsing and result ingress handoff.

`EmbeddedAdapterStarterDefaults` owns the fixed first-party factory registry.
It may know the bundled adapter set. It must not become a dynamic plugin loader.

`EmbeddedAdapterStarter` owns:

- `spec.type -> factory` lookup;
- `adapterId -> runtime` registry;
- runtime create/start/close;
- registration and binding resolution after runtimes exist.

`embedded-sdk` / starter assembly owns:

- user configuration translation into embedded adapter specs;
- object sidecar validation and handoff to the factory owner;
- backend port construction such as queue, result, evidence, and executor.
- pre-runtime descriptor-based registration normalization when adapter runtimes
  have not been created yet.

It should not duplicate adapter runtime internals or adapter type metadata.

`TransportConfig` belongs to embedded-sdk/builder configuration. It is a
mutable declaration accumulator, not transport runtime truth.

`TransportRuntimeComposition` is allowed to be an immutable assembly snapshot
and narrow projection surface. It should not own adapter spec construction,
descriptor truth, registration resolver truth, or lazy runtime resources.

`EmbeddedAdapterSpecAssembler`, if introduced, belongs in
`sdk/xa-mass-embedded-sdk` under the starter/config implementation package, for
example `com.xa.mass.starter.config.EmbeddedAdapterSpecAssembler`. It consumes
SDK/starter typed declarations such as `TransportConfig`,
`TransportConfig.WebSocketAdapterAssembly`, `WebSocketAdapterConfig`, and
`SocketAdapterConfig`. It must not live in `transport_runtime` or
`transport/adapter-starter`, because that would push SDK typed configuration
back into transport.

`adapter-starter` owns only fixed factory registry, descriptor generation from
already-built specs, and runtime create/start/close. It must not import
`com.xa.mass.starter.config.*` or read SDK typed config classes.

## Boundary Decision

The embedded adapter startup contract should be:

```text
typed SDK config / builder sugar
  -> TransportConfig mutable declaration accumulator
  -> TransportRuntimeComposition immutable snapshot
  -> internal adapter spec assembler / sidecar validator
  -> internal List<EmbeddedAdapterRuntimeSpec>
  -> fixed built-in factory registry
  -> EmbeddedTransportAdapterRuntimeFactory.descriptor(spec)
  -> EmbeddedTransportAdapterRuntimeFactory.create(spec, environment)
  -> EmbeddedTransportAdapterRuntime
```

The adapter type is interpreted by one owner: the fixed factory registry.
Descriptor and transport hint truth must come from
`EmbeddedTransportAdapterRuntimeFactory.descriptor(spec)`, not from a parallel
`type -> hint` switch.

The canonical spec list is internal runtime assembly truth. Public
`MassSdk.TransportOptions` should keep typed adapter options by default. A raw
`addSpec(EmbeddedAdapterRuntimeSpec)` public API would be an advanced
embedded-only API and requires a separate decision.

`TransportConfig` and `TransportRuntimeComposition` may remain after this
roadmap, but their target roles are intentionally smaller:

```text
TransportConfig
  = mutable embedded-sdk/builder declaration accumulator

TransportRuntimeComposition
  = immutable snapshot and simple projection surface

EmbeddedAdapterSpecAssembler
  = typed adapter declarations + sidecars -> final specs + enabled projection
  = sdk/xa-mass-embedded-sdk starter/config internal implementation

EmbeddedAdapterStarter / factory registry
  = type resolution, descriptor truth from specs, runtime create/start/close
```

## Embedded SDK Interaction Model

After this roadmap, embedded SDK should interact with adapters like this:

```text
MassSdk / builder typed adapter options
  -> TransportConfig snapshot
  -> TransportRuntimeComposition immutable declaration snapshot
  -> internal adapter spec assembler final specs + sidecars
  -> EmbeddedAdapterStarter.create(specs)
  -> EmbeddedAdapterStarter.startAll()/close()
```

`embedded-sdk` may construct shared backend ports once during application
assembly:

- dispatch queue;
- result ingress queue/sink;
- endpoint lease/evidence store;
- negative disconnect sink;
- runtime executor;
- polling pending delivery buffer factory;
- object sidecars such as WebSocket custom server factories.

`embedded-sdk` must not construct adapter runtime internals, inspect protocol
types during startup, hold `EmbeddedTransportAdapterRuntime` objects, receive
runtime sets/snapshots, or pass raw queue/store/executor objects through
per-adapter specs/options. The per-adapter input remains the small
`EmbeddedAdapterRuntimeSpec` plus sidecars owned by the adapter factory.

The starter owns `adapterId -> runtime`. `embedded-sdk` may use the starter for
lifecycle and resolution operations that are already part of the embedded
starter surface, such as `create(specs)`, `startAll()`, `close()`,
`resolveRegistrationAdapterId(...)`, and pull-worker transport resolution. It
should not bypass the starter to call concrete adapter modules directly.

When adapter runtimes have not been created, such as producer-only startup or
early worker registration normalization, `embedded-sdk` must use a
descriptor-only resolver built from the same fixed factory registry and final
specs. It must not keep a parallel `type -> transportHint` switch inside
`TransportRuntimeComposition`.

## Decisions For This Roadmap

These decisions are fixed for this roadmap:

- Do not expose raw `EmbeddedAdapterRuntimeSpec` through public
  `MassSdk.TransportOptions` or default public builder APIs.
- Keep first-party typed adapter config as public/builder sugar.
- Include targeted `TransportConfig` / `TransportRuntimeComposition` shrink in
  this roadmap. Do not require deleting either class in this roadmap.
- `TransportConfig` may keep typed config fields, builder-facing mutators, and
  package-private helper state, but it must not expose raw specs as the default
  public API or act as runtime assembly truth.
- `TransportRuntimeComposition` may keep immutable copied values and simple
  projections, but adapter spec construction, descriptor resolution, and
  registration resolver ownership must move out.
- `EmbeddedAdapterSpecAssembler` is an SDK/starter internal implementation
  detail, not a transport-runtime or adapter-starter class.
- Keep polling as the default built-in adapter. Do not add a polling disable
  switch in this roadmap.
- Keep `isEnabled()` semantics separate from final spec-list existence.
  `isEnabled()` means user-enabled push/extra adapter declarations exist.
  Default polling and server-only WebSocket configuration do not make it true.
- Allow `EmbeddedAdapterRuntimeSpec` construction only in a named internal
  spec assembler/translator. Do not globally forbid the constructor; forbid
  scattered construction in runtime composition, application startup, or
  adapter-starter registry code.
- Use a small fixed registry/resolver class in `adapter-starter` for built-in
  factories and descriptors. Do not solve this by broadening
  `defaultFactories(...)` into a loose public list API.
- The fixed registry must support descriptor-only resolution from specs without
  creating adapter runtimes, so `MassApplication.resolveRegistrationAdapterId`
  can work before or without a live `EmbeddedAdapterStarter`.
- Enforce WebSocket server factory sidecar consistency with fail-fast rules.
- Keep `ManagedTransportAdapter`, `AdapterDispatchQueueConsumer`, and
  `AdapterMailboxConsumer` cleanup out of this roadmap.

## Target Shape

Adding a new built-in adapter type should require:

```text
1. Add transport/<adapter>-adapter module.
2. Implement <Adapter>RuntimeFactory.
3. Add the factory to EmbeddedAdapterStarterDefaults fixed registry.
4. Add optional typed SDK/builder sugar in `sdk/xa-mass-embedded-sdk`
   starter/config that compiles to `EmbeddedAdapterRuntimeSpec`.
5. Add focused factory/starter/config tests.
```

It should not require scattered runtime composition switches:

```text
TransportRuntimeComposition.resolveEmbeddedAdapterRuntimeSpecs()
EmbeddedAdapterStarterDefaults.transportHintForType()
TransportConfig per-adapter storage truth
TransportRuntimeComposition registrationResolver()
TransportRuntimeComposition runtimeOwnedEndpointLeaseStore
MassApplication adapter-specific startup branches
```

Typed config classes can remain as public/builder convenience, but they must
compile down to internal specs before runtime composition.

`TransportConfig` and `TransportRuntimeComposition` target shape:

- `TransportConfig` remains a mutable embedded-sdk config accumulator.
- `TransportRuntimeComposition` remains a copied immutable snapshot, or is
  renamed later if a smaller name becomes worthwhile.
- Spec construction lives in one internal assembler/translator.
- Descriptor and transport hint truth lives in factory descriptors through the
  fixed registry.
- Runtime resource creation lives in SDK/starter assembly or a narrow backend
  resource factory, not as lazy mutable state inside composition.

## Non-Goals

- Do not implement a gRPC adapter in this roadmap.
- Do not add dynamic discovery, classpath scanning, Java `ServiceLoader`, remote
  adapter registration, or plugin loading.
- Do not turn adapter-starter into a general extension framework.
- Do not expose raw `EmbeddedAdapterRuntimeSpec` as default public `MassSdk`
  API.
- Do not remove `TransportServer` or custom WebSocket server factory in this
  roadmap.
- Do not rename `dispatchQueueKey`, `adapterMailboxKey`, or result queue
  vocabulary in this roadmap.
- Do not solve `ManagedTransportAdapter` / `TransportServer` resource naming in
  this roadmap.
- Do not expose raw queue primitives to adapter modules or SDK callers.
- Do not fully delete `TransportConfig` or `TransportRuntimeComposition` in
  this roadmap.
- Do not redesign all SDK transport options. This roadmap only reduces
  responsibilities that block spec-driven embedded adapter startup.
- Do not rename `TransportRuntimeComposition` unless implementation makes the
  smaller role obvious and the rename is low-cost.

## Do Not Start With

Do not start by adding dynamic discovery or a plugin SPI. The desired state is
fixed built-in adapters, started from explicit specs.

Do not start by making specs public SDK API. Specs are internal runtime assembly
truth first.

Do not start by deleting WebSocket/Socket typed config classes. First make
specs the canonical runtime input, then decide which typed builder sugar is
still useful.

Do not start by making `EmbeddedAdapterRuntimeSpec.options` carry object hooks.
Object extensions such as custom WebSocket server factories must remain sidecar
factory dependencies until a separate hook owner is designed.

Do not start by deleting `TransportConfig` or `TransportRuntimeComposition`.
First move spec construction, descriptor resolution, and runtime-resource lazy
ownership to the correct owners, then decide whether the remaining snapshot
shape deserves a rename or deletion.

## EAS-0 Inventory And Caller Classification

Goal:

Classify current spec writers, factory writers, descriptor readers, sidecars,
and guard assumptions before changing assembly.

Scope:

- `TransportConfig`
- `TransportRuntimeComposition`
- `MassApplication`
- `EmbeddedAdapterStarter`
- `EmbeddedAdapterStarterDefaults`
- `WebSocketAdapterRuntimeFactory`
- `SocketAdapterRuntimeFactory`
- `PollingAdapterRuntimeFactory`
- `TransportConvergenceArchitectureGuardTest`
- SDK builder/tests that configure built-in adapters

Acceptance:

- Inventory identifies all production writers of `EmbeddedAdapterRuntimeSpec`.
- Inventory identifies all callers of
  `EmbeddedAdapterStarterDefaults.transportHintForType(...)`.
- Inventory identifies architecture guards that currently preserve old spec
  generation, especially the `new EmbeddedAdapterRuntimeSpec(` expectation.
- Inventory separates string options from object sidecars such as
  `TransportServerFactory<WebSocketServerFactoryContext>`.
- Inventory states which typed config classes are public/builder sugar and which
  fields are runtime assembly truth.
- Inventory identifies all `isEnabled()` callers and records the preserved
  public meaning: user-enabled push/extra adapter declarations only, excluding
  default polling.
- Inventory classifies every `TransportRuntimeComposition` method as one of:
  snapshot getter/projection, backend resource factory, adapter spec assembly,
  descriptor/registration resolution, or runtime lazy owner.
- Inventory records which `TransportRuntimeComposition` responsibilities are
  removed in this roadmap and which are explicitly deferred.

## EAS-1 Fixed Registry And Descriptor Resolution

Goal:

Make the fixed built-in factory registry reusable before descriptor resolution
moves to factory descriptors.

Scope:

- Introduce a small registry/resolver surface in `adapter-starter`, such as
  `EmbeddedAdapterRuntimeFactoryRegistry`, so the same built-in factories serve
  both runtime creation and descriptor resolution.
- Keep factory construction fixed and explicit.
- `EmbeddedAdapterStarterDefaults` should create this fixed registry from
  first-party factories and adapter-specific sidecars.
- Move registration descriptor resolution to
  `EmbeddedTransportAdapterRuntimeFactory.descriptor(spec)`.
- Provide a descriptor-only resolver path from final specs, such as
  `registry.descriptors(specs)` or `registry.registrationResolver(specs)`, that
  does not create `EmbeddedTransportAdapterRuntime` instances.
- Delete `EmbeddedAdapterStarterDefaults.transportHintForType(...)` after all
  callers migrate.
- Add `PollingAdapterRuntimeFactoryTest` before listing it as completion proof.
- Update any architecture guard that would block this slice in the same slice.

Acceptance:

- `TransportRuntimeComposition` no longer derives transport hints from a
  `type -> hint` switch.
- No production code calls `transportHintForType(...)`.
- Unknown `spec.type()` fails through the fixed registry with a clear error.
- The same built-in factory set is used for descriptor resolution and runtime
  creation.
- `MassApplication.resolveRegistrationAdapterId(...)` works when
  `embeddedAdapterStarter == null` by using descriptor-only resolution from the
  fixed registry and final specs, not `TransportRuntimeComposition` descriptor
  construction.
- No `ServiceLoader`, reflection scan, classpath scan, or dynamic plugin lookup
  is introduced.
- Focused tests prove descriptor generation for polling/WebSocket/Socket specs
  comes from factories.
- `PollingAdapterRuntimeFactoryTest` exists and proves descriptor/binding basics
  for polling.

## EAS-2 Canonical Spec Assembler And Sidecar Consistency

Goal:

Make a single internal assembler own the final spec list and sidecar
consistency without exposing specs as the default public SDK API.

Scope:

- Introduce or identify a named internal spec assembler/translator, such as
  `EmbeddedAdapterSpecAssembler`.
- Place the assembler in `sdk/xa-mass-embedded-sdk` starter/config internals,
  not in `transport_runtime` or `transport/adapter-starter`.
- Feed the assembler from the immutable `TransportRuntimeComposition` snapshot,
  not from live mutable `TransportConfig`.
- Convert built-in typed config convenience paths into spec appends or spec
  replacement inside that assembler.
- Preserve polling as the default spec. Do not add a polling disable switch in
  this roadmap.
- Preserve unique adapter id validation over the final spec list.
- Make `TransportConfig.isEnabled()` and `TransportRuntimeComposition.isEnabled()`
  derive from the same declaration snapshot as spec assembly, but not from
  final spec-list existence. The default polling spec must be excluded from the
  public enabled projection.
- Keep public `MassSdk` on typed adapter options. Do not add raw public spec API
  in this slice.
- Validate object sidecars against the final spec list.

Sidecar rules:

- A sidecar must be produced from the same append/translation path as its spec.
- A sidecar adapter id must exist in the final spec list.
- Disabled/replaced adapter specs must not leave active sidecars.
- Duplicate sidecars for the same adapter id fail fast.
- Orphan sidecars fail fast before adapter runtime creation.

Acceptance:

- `TransportRuntimeComposition` no longer constructs polling/WebSocket/Socket
  specs directly.
- One named internal assembler/translator constructs the canonical final spec
  list.
- The assembler is package-private or otherwise internal to
  `sdk/xa-mass-embedded-sdk` starter/config unless a separate public API
  decision proves otherwise.
- `transport/adapter-starter` and `transport_runtime` do not import
  `com.xa.mass.starter.config.*` to assemble specs.
- Existing defaults still produce polling plus enabled WebSocket/Socket specs.
- Polling remains present in the final spec list for embedded runtime unless a
  separate future roadmap defines a disable policy.
- WebSocket server factory sidecars are consistent with the final spec list and
  cannot target missing/disabled/replaced adapter ids.
- `isEnabled()` remains false when only the default polling spec exists or when
  WebSocket server-only configuration is enabled. It becomes true only for
  user-enabled push/extra adapter declarations.
- `isEnabled()` does not preserve a parallel WebSocket/Socket typed-field truth;
  the same declaration translator that produces specs also produces this
  user-enabled projection.
- Public SDK/builder APIs do not expose raw
  `EmbeddedAdapterRuntimeSpec` as the default configuration mechanism.
- SDK/builder tests prove WebSocket, Socket, supplemental adapters, sidecars,
  and polling default specs still resolve.
- Architecture guards that previously required `new EmbeddedAdapterRuntimeSpec(`
  in composition are updated in this slice.
- No compatibility alias is kept for an old parallel spec-generation path.

## EAS-3 Shrink Transport Runtime Composition Residue

Goal:

Make `TransportRuntimeComposition` a smaller immutable snapshot/projection
surface and keep `MassApplication` on the generic starter path it already uses.

Scope:

- Treat `MassApplication` as proof-first unless inventory finds new
  adapter-type-specific startup branching. It should create adapter-starter
  with environment and fixed built-in factory defaults, then call
  `create(specs)` with the final spec list.
- Remove helper methods such as `webSocketSpec(...)` and `socketSpec(...)`
  after typed config has compiled down to specs earlier.
- Keep adapter-specific object sidecar assembly in one place, not split between
  `MassApplication` and `TransportRuntimeComposition`.
- Move descriptor/registration resolver ownership out of
  `TransportRuntimeComposition` after fixed factory descriptor resolution is in
  place.
- Move lazy runtime-owned resource state out of `TransportRuntimeComposition`
  where it is directly coupled to adapter startup, especially
  `runtimeOwnedEndpointLeaseStore`.
- Resolve each backend runtime resource once per `MassApplication` startup and
  pass the same instance to adapter environment, registration/resolution paths,
  endpoint lease users, result ingress users, and queue users that share that
  resource.
- Keep backend factory getters/projections only when moving them would expand
  this roadmap into unrelated transport resource assembly work; record any
  remaining resource-factory projection as explicit residue.

Acceptance:

- `MassApplication` remains free of WebSocket/Socket/Polling type switches when
  starting embedded adapters.
- `TransportRuntimeComposition` does not contain WebSocket/Socket/Polling
  spec-construction branches as runtime truth.
- `TransportRuntimeComposition` does not own registration descriptor resolution
  or cache a `TransportRegistrationResolver`.
- `TransportRuntimeComposition` does not cache runtime-owned mutable resources
  such as endpoint lease stores.
- SDK/starter assembly resolves backend runtime resources once per
  `MassApplication` startup. Focused tests prove the same endpoint lease store
  instance is used by the adapter environment and all lease/resolution users
  that need it.
- Adapter-specific server factory sidecars are passed only to the factory owner
  that needs them.
- Any remaining `TransportRuntimeComposition` methods are either immutable
  snapshot getters or simple projections with a named caller.
- Embedded runtime startup tests prove create/start/close still work for
  polling, WebSocket, and Socket.

## EAS-4 Guards, Docs, And Proof Registry

Goal:

Freeze the simplified adapter assembly boundary.

Scope:

- Update `transport/AGENTS.md`.
- Update `transport/TRANSPORT_BOUNDARY_BASELINE.md`.
- Update `doc/PROOF_REGISTRY.md`.
- Add or update architecture guards.

Acceptance:

- Guard fails if `TransportRuntimeComposition` reintroduces hard-coded
  WebSocket/Socket/Polling spec construction as runtime truth.
- Guard fails if production code calls `transportHintForType(...)`.
- Guard fails if dynamic discovery mechanisms are introduced for embedded
  adapter factories in main sources. Test-only `Class.forName(...)` checks that
  prove removed classes stay absent are allowed.
- Guard allows typed public config sugar only when it compiles to the internal
  spec list.
- Guard fails if `transport_runtime` or `transport/adapter-starter` imports
  `com.xa.mass.starter.config.*` to access SDK typed config or the spec
  assembler.
- Guard fails if `TransportRuntimeComposition` reintroduces descriptor
  resolution, registration resolver caching, or runtime resource lazy ownership
  after this roadmap removes them.
- Guard does not globally forbid `new EmbeddedAdapterRuntimeSpec(...)`; it
  allows the named internal spec assembler/translator and forbids scattered
  construction in runtime composition, `MassApplication`, and adapter-starter
  registry code.
- Proof registry names focused tests for factory registry, spec generation,
  sidecar consistency, starter create/start, polling factory, and SDK builder
  config.

## Suggested Implementation Order

1. EAS-0 inventory current spec/factory/descriptor/sidecar/guard callers.
2. EAS-1 create the fixed registry/descriptor resolver and remove
   `transportHintForType(...)`.
3. EAS-2 add the canonical spec assembler and enforce sidecar consistency.
4. EAS-3 shrink `TransportRuntimeComposition` residue and remove runtime-type
   branches, descriptor resolver ownership, and lazy runtime-owned state.
5. EAS-4 add final guards and update docs/proof registry.

This order avoids the previous break: descriptor resolution can move only after
the fixed factory registry is reusable.

## Verification Candidates

Compile:

```powershell
.\mvnw.cmd -q -pl transport/transport_api,transport/transport_runtime,transport/adapter-starter,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk -am -DskipTests test-compile
```

Focused tests:

```powershell
.\mvnw.cmd -q -pl transport/adapter-starter test "-Dtest=EmbeddedAdapterStarterTest" "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl transport/transport_runtime test "-Dtest=CompositeEmbeddedTransportAdapterRuntimeTest,TransportConvergenceArchitectureGuardTest" "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl transport/polling-adapter test "-Dtest=PollingAdapterRuntimeFactoryTest" "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl transport/socket-adapter test "-Dtest=SocketAdapterRuntimeFactoryTest" "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl transport/websocket-adapter test "-Dtest=WebSocketAdapterRuntimeFactoryTest" "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk test "-Dtest=TransportConfigTest,MassApplicationDistributedTransportTest,MassSdkTest" "-DtrimStackTrace=true"
```

`PollingAdapterRuntimeFactoryTest` is part of this roadmap scope and must be
present before the focused commands can be treated as completion proof.
Do not use `-Dsurefire.failIfNoSpecifiedTests=false` for completion proof.

Residue scans:

```powershell
rg -n "transportHintForType|webSocketSpec\\(|socketSpec\\(|EmbeddedAdapterStarterDefaults\\.TYPE_WEBSOCKET|EmbeddedAdapterStarterDefaults\\.TYPE_SOCKET" sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/config/TransportRuntimeComposition.java transport/adapter-starter/src/main/java transport/transport_runtime/src/main/java --glob "*.java" --glob "!**/target/**"
rg -n "new EmbeddedAdapterRuntimeSpec\\(" sdk/xa-mass-embedded-sdk/src/main/java transport/adapter-starter/src/main/java transport/transport_runtime/src/main/java --glob "*.java" --glob "!**/target/**"
rg -n "ServiceLoader|ClassLoader|getResources\\(|Class\\.forName|Reflections|classpath" transport sdk --glob "*.java" --glob "!**/src/test/**" --glob "!**/target/**"
```

The `new EmbeddedAdapterRuntimeSpec(...)` scan is not a zero-hit scan. Expected
allowed hit: only `EmbeddedAdapterSpecAssembler` or its named successor under
`sdk/xa-mass-embedded-sdk` starter/config internals. The guard should deny old
locations such as `TransportRuntimeComposition`, `MassApplication`,
`transport_runtime`, and `transport/adapter-starter` registry code.

Expected after completion:

- Spec list is the canonical runtime assembly input.
- `new EmbeddedAdapterRuntimeSpec(...)` appears only in the named internal spec
  assembler/translator, not in `MassApplication`, `TransportRuntimeComposition`,
  or factory registry code.
- `TransportConfig` remains only embedded-sdk typed config / backend option
  input, not runtime spec or descriptor truth.
- `TransportRuntimeComposition` remains only immutable snapshot/projection
  surface, not adapter spec builder, descriptor resolver, or lazy runtime
  resource owner.
- Fixed built-in factory registry remains explicit.
- Descriptor-only registration normalization works before adapter runtimes are
  created and uses factory descriptors from final specs.
- Backend runtime resources are resolved once per `MassApplication` startup and
  shared through assembly, not lazily owned by `TransportRuntimeComposition`.
- No dynamic adapter discovery exists.
- Descriptor and transport hint truth comes from factory descriptor methods.
- Runtime composition no longer owns adapter type-specific spec construction.
- WebSocket sidecars cannot outlive or bypass final specs.

## Roadmap Completion Criteria

The roadmap can be marked complete only when:

- embedded SDK startup can launch built-in adapters from internal
  `EmbeddedAdapterRuntimeSpec` records without type-specific runtime
  composition branches;
- built-in factory registry owns `spec.type -> factory`;
- descriptor/hint resolution comes from factories, not a parallel type switch;
- descriptor-only registration normalization works before adapter runtimes are
  created and does not depend on live `EmbeddedAdapterStarter` runtime
  instances;
- first-party adapter typed config, if still present, is only public/builder
  sugar that produces specs;
- spec assembly from SDK typed config is owned by an internal
  `sdk/xa-mass-embedded-sdk` starter/config assembler, not by
  `transport_runtime` or `transport/adapter-starter`;
- `TransportConfig` and `TransportRuntimeComposition` are reduced to their
  explicit SDK config/snapshot roles, with any remaining projections named and
  justified;
- backend runtime resources are resolved once per `MassApplication` startup and
  shared through assembly instead of lazily owned by
  `TransportRuntimeComposition`;
- sidecars are validated against the final spec list;
- polling default behavior is preserved without adding a new disable switch;
- `PollingAdapterRuntimeFactoryTest` exists and participates in focused proof;
- docs and proof registry describe spec-driven fixed built-in adapter assembly;
- guards prevent dynamic discovery, raw default public spec API, and
  reintroduction of parallel type switches.
