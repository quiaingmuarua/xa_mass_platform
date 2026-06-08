# SDK And Integrations Boundary Guard

Status: current global guard for SDK and integrations ownership.

Use this document before changing `sdk/`, `integrations/`, external worker
contracts, public Controller DTOs, or server bootstrapping. It is an agent guard,
not a roadmap. It records the boundary rules that must survive session changes,
context compaction, and roadmap handoff.

## Short Rule

`sdk/` owns reusable caller contracts. `integrations/` owns real adopters,
worker capability packs, and protocol/dev fixtures. Server startup must not
replace external registration with privileged task or worker seeding.
Runtime/deployment profiles may differ by memory vs persistent infrastructure,
seed source, logging, local convenience, and fail-closed validation, but they
must not expose different public API contracts or authentication bypass
semantics.

Seedability is decided by truth ownership, not by active profile. Profiles may
change infra mode and secret source; they must not change whether a resource is
initialized by seed/import, public SDK/API calls, or runtime execution.
Memory infrastructure can be a legitimate deployment mode for immediate or
RPC-style workloads; it must still use the same public API, API-key, operator
auth, seed/import, and worker registration rules as persistent infrastructure.
Fixture-header authentication is a support-test/operator-console fixture, not a
property of memory infrastructure. Trusted-auth proof may run on memory or
persistent infra, but it must use session/CSRF for operator mutations and API
keys for SDK/worker-api calls.

## Owner Map

| Area | Owner | Allowed role |
| --- | --- | --- |
| `sdk/xa-mass-java-sdk` | external Java SDK | public Java entry for task producers and external worker processes talking to a running server |
| `sdk/xa-mass-public-contract` | public Controller wire contract | narrow DTOs/constants exposed by Controllers and needed by external SDK callers |
| `sdk/xa-mass-embedded-sdk-api` | embedded SDK API | embedded/runtime-facing contracts; may describe in-process composition APIs |
| `sdk/xa-mass-embedded-sdk` | embedded JVM SDK | in-process runtime composition for JVM callers |
| `integrations/xa-mass-scenario-launcher` | Java external SDK adopter | executable proof that external SDK registration, worker sessions, and task submission work against a running server |
| `integrations/xa-mass-worker-pack` | worker capability pack | reusable worker capabilities plus explicitly documented dev/E2E harness support |
| `integrations/samples` | protocol/dev fixtures | external-process validation fixtures only; not the public SDK product surface |
| `xa-mass-server` | reference host | HTTP/auth/project/tenant/user/API-key/console shell; validates public paths without redefining kernel ownership |

## Hard Rules

- `xa-mass-java-sdk` production code must not depend on `xa-mass-server`,
  `xa-mass-engine`, `xa-mass-base`, embedded SDK modules, worker-pack modules,
  transport implementation modules, Spring Boot, or platform runtime assembly.
- `xa-mass-public-contract` may only contain Controller-exposed wire
  DTOs/constants that are recorded in
  [`sdk/xa-mass-public-contract/README.md`](../sdk/xa-mass-public-contract/README.md)
  with the owning Controller method and route role.
- Do not move control-plane internals, review materialization models,
  diagnostics, bootstrap fixtures, transport frames, embedded runtime assembly,
  or worker-pack capability models into `xa-mass-public-contract`.
- Do not move worker capability code into SDK modules. Capability packs belong
  under `integrations/`.
- Do not make `integrations/samples` the Java product entry or a parallel public
  SDK surface. Java SDK-backed execution belongs in
  `integrations/xa-mass-scenario-launcher` or a real integration module.
- Worker-pack may use embedded SDK dependencies only for explicitly documented
  dev-shell or E2E harness paths. Production capability code should register and
  run through the same public SDK/API paths used by external actors.
- A platform-provided worker group is not privileged. It must be declared,
  registered, made online, and fed tasks through the same public registration
  and task submission paths as an external worker.
- `xa-mass-server` production startup must not seed task, worker, WorkerGroup,
  AdapterNode, or NodeGroupBinding truth as a substitute for external
  registration.
- Do not add or extend dev-only HTTP APIs such as sample bootstrap routes to
  prepare project/rule/catalog/credential data. New-environment setup belongs
  to explicit control-plane seed/import tooling, not to SDK/integrations
  runtime paths.
- SQLite-backed seed/import work may initialize only control-plane storage.
  Redis/runtime truth and trace/audit materialization remain separate infra
  layers; see [`INFRA_TRUTH_LAYERS.md`](./INFRA_TRUTH_LAYERS.md).
- Transport-specific frame shapes must not redefine event-handler,
  WorkerGroup, AdapterNode, task, result, or scheduling semantics.
- API-key raw secrets may appear only as seed/import material or one-time
  create responses. Durable API-key truth is hash, prefix, scopes,
  permissions, owner, status, expiry, and metadata.
- Task producer SDK paths may create tasks, append items, and read accepted
  results. Seal, approve, pause, resume, cancel, and equivalent lifecycle
  commands are operator/server-control actions unless a separate authorization
  decision explicitly changes that boundary.

## Seed Taxonomy

| Category | Seed/import stance | Examples | Owner rule |
| --- | --- | --- | --- |
| Control-plane config | Allowed | projects, events/catalog, rule and policy configuration | Stable server-owned configuration may be idempotently imported. |
| Credential bootstrap | Allowed with secret-source controls | operator credentials, API keys | Raw secrets are import material only; stored truth is credential metadata and hash/projection state. |
| Runtime actor registration | Must use SDK or worker API | WorkerGroup, AdapterNode, NodeGroupBinding, Worker, worker online/state/capability reports | External actor and topology evidence must enter through the same registration path production workers use. |
| Workload injection | Must use SDK or task API | task create, item append, task result reads | Workload is caller behavior, not server initialization. |
| Operator lifecycle command | Must use operator/server-control API | seal, approve, pause, resume, cancel, worker command requests | Control actions require the operator authorization boundary. |
| Runtime truth | Forbidden | ready/delayed queues, leases, assignments, reservations, occupancy, route buckets, dispatch gates, final result rows, command status | Runtime owners derive and mutate these facts; seed/import must not precompute or restore them. |
| Generated evidence | Forbidden as seed | usage, trace, audit rows, sessions, CSRF tokens | Evidence is emitted by real accepted/rejected operations. A seed/import operation may produce audit evidence; it must not seed audit evidence. |

WorkerGroup is currently runtime actor registration truth and must be declared
through the worker API or Java SDK. If it later becomes seedable, the change must
first define a control-plane owner, persistence shape, and runtime projection so
seeded WorkerGroup capability cannot coexist with worker-declared capability as
two live truths.

## Allowed Patterns

- External Java task producers and workers use `MassPlatform` and SDK session
  APIs from `sdk/xa-mass-java-sdk`.
- SDK request/response builders may wrap public-contract DTOs when that keeps
  caller ergonomics stable.
- Server Controllers may consume public-contract DTOs, then convert them to
  server/embedded/runtime models inside the server boundary.
- `integrations/xa-mass-scenario-launcher` may compose SDK calls into an
  executable scenario that proves real external registration, worker sessions,
  task creation, item append, and result convergence.
- Scenario-launcher should assume the target server environment has already
  been initialized through real control-plane storage or explicit seed/import;
  it should not call server-owned sample bootstrap APIs as part of the SDK
  proof path.
- `integrations/xa-mass-worker-pack` may provide reusable business
  capabilities such as `tool.geo.lookup`, plus separated harness packages for
  dev/E2E proof.
- `integrations/samples` may keep Node/polling/WebSocket/socket fixtures for
  cross-language protocol validation.
- `sdk/xa-mass-java-sdk` typed clients, such as `WorkerClient`, may own public
  platform route literals. Adopter modules should call those typed clients or
  sessions instead of hard-coding the same routes.

## Forbidden Drift

Stop and review the boundary if a change does any of the following:

- adds an SDK dependency on server, engine, base, transport implementation,
  worker-pack, or embedded SDK modules;
- adds a type to `xa-mass-public-contract` without an inventory row naming the
  exact Controller method and route role;
- copies engine/base/server models into SDK to avoid defining a narrow public
  contract;
- makes worker-pack command/fault harness behavior part of the external Java
  SDK;
- treats samples as the Java SDK adoption path;
- adds server startup code that silently creates production task or worker
  truth;
- adds a memory/persistent or local/production API split for project, rule,
  catalog, credential, task, or worker setup instead of changing only
  infrastructure, seed source, or operational defaults;
- exposes raw worker or bootstrap platform APIs from worker-pack instead of
  routing real worker behavior through the Java SDK;
- introduces a transport-specific handler runtime that changes event-handler
  semantics instead of adapting transport to the handler contract;
- preserves old and new SDK/integration paths as parallel live tracks after an
  owner decision has been made.

## Mechanical Guard Expectations

When touching these boundaries, prefer source or dependency guards that fail on
new drift:

- SDK production dependency guard for `xa-mass-java-sdk`.
- Public-contract candidate guard against non-Controller DTOs and missing
  inventory rows.
- Integration source guard against hard-coded platform route literals in
  adopter modules when a typed SDK client/session exists. The SDK typed route
  owner itself is the allowlisted location for those literals.
- Worker-pack package guard that keeps production capability packages separate
  from sample/dev harness packages.
- Server startup guard that prevents production boot seeding of task/worker
  truth.

If a guard cannot be implemented in the same slice, record the missing guard in
the owning roadmap or inventory before changing the boundary.

## Reading Order

1. [`sdk/README.md`](../sdk/README.md)
2. [`integrations/README.md`](../integrations/README.md)
3. [`sdk/xa-mass-public-contract/README.md`](../sdk/xa-mass-public-contract/README.md)
4. [`EXTERNAL_SDK_QUICKSTART.md`](../sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md)
5. [`integrations/xa-mass-worker-pack/README.md`](../integrations/xa-mass-worker-pack/README.md)
