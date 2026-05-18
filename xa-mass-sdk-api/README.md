# xa-mass-sdk-api

Status: current SDK contract owner README.

`xa-mass-sdk-api` owns stable SDK-facing contract types that should be usable by
embedding callers without pulling in runtime composition internals.

## Role

- public SDK auth types under `com.xa.mass.sdk.auth`
- public SDK authorization and ownership contract types under `com.xa.mass.sdk.authz`
- public SDK catalog/event/model contracts under `com.xa.mass.sdk.catalog`,
  `com.xa.mass.sdk.event`, and `com.xa.mass.sdk.model`
- shared SDK contract surface used by `xa-mass-sdk`, `xa-mass-server`, and
  tests

## What Belongs Here

- request/response and registration models intended for SDK callers
- owner-backed worker-control and task-stage evidence request/snapshot models
  intended for SDK callers
- submitter/principal contract types and small in-memory SDK-local helpers
- platform-level authorization request/policy contracts and minimal ownership contracts
- catalog and event-definition contract types

## What Does Not Belong Here

- embedded runtime assembly
- engine/bootstrap wiring
- transport runtime ownership
- control-console or HTTP controller behavior
- infra implementation ownership such as JDBC or in-memory storage backends

## Current Boundaries

- `xa-mass-sdk-api` is the contract artifact
- `xa-mass-sdk` is the embedding/runtime-composition artifact
- SDK contracts are the stable integration boundary for workers, embedding
  clients, and external automation
- `xa-mass-server` may adapt these contracts into HTTP/auth/project/tenant/user
  flows, but server host concerns must not redefine kernel semantics inside the
  SDK contract layer
- security model ownership starts here: `PrincipalContext`, submitter credentials, `AuthorizationRequest`, `AuthorizationPolicy`, and `TaskOwnershipStamp` are SDK contracts, not server-only types
- infra modules must not export `com.xa.mass.sdk.*` ownership back out of this
  module family

Read-model rule:

- SDK request models and SDK snapshot models preserve public compatibility while
  engine/base models continue to evolve
- SDK snapshots are read-model boundaries, not runtime truth and not valid
  engine decision input
- do not mirror every internal `Task` / `Worker` / runtime field into SDK
  snapshots unless it is needed as a stable external contract
- worker-control and stage-evidence SDK models are adapters over engine
  owner-backed services. They must not expose engine owner records directly and
  must not redefine command, capability, state, or final-result semantics.

## Security Contract Surface

- `com.xa.mass.sdk.auth.PrincipalContext` is the shared authenticated caller shape
- `com.xa.mass.sdk.authz.AuthorizationRequest` + `AuthorizationPolicy` are the unified control-plane authorization entrypoint
- `AuthorizationDecision` now carries both human-readable `reason` and structured `AuthorizationReasonCode`, so hosts do not need to infer deny semantics from string prefixes
- `PlatformResourceType` and `PlatformAction` provide the current minimal platform resource/action vocabulary
- `TaskOwnershipStamp` is the minimal framework-owned task ownership envelope currently persisted under the reserved internal key `Task.sharedConfig._massSecurity`; hosts should expose an explicit derived read model instead of teaching callers to parse that envelope directly
- `xa-mass-server` and other hosts should adapt transport or HTTP details into these contracts instead of inventing host-local permission truth

## Start Here

- `src/main/java/com/xa/mass/sdk/auth`
- `src/main/java/com/xa/mass/sdk/authz`
- `src/main/java/com/xa/mass/sdk/catalog`
- `src/main/java/com/xa/mass/sdk/event`
- `src/main/java/com/xa/mass/sdk/model`
