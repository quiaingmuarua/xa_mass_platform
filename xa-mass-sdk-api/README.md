# xa-mass-sdk-api

Status: current SDK contract owner README.

`xa-mass-sdk-api` owns stable SDK-facing contract types that should be usable by
embedding callers without pulling in runtime composition internals.

## Role

- public SDK auth types under `com.xa.mass.sdk.auth`
- public SDK catalog/event/model contracts under `com.xa.mass.sdk.catalog`,
  `com.xa.mass.sdk.event`, and `com.xa.mass.sdk.model`
- shared SDK contract surface used by `xa-mass-sdk`, `xa-mass-server`, and
  tests

## What Belongs Here

- request/response and registration models intended for SDK callers
- submitter/principal contract types and small in-memory SDK-local helpers
- catalog metadata and event-definition contract types

## What Does Not Belong Here

- embedded runtime assembly
- engine/bootstrap wiring
- transport runtime ownership
- control-console or HTTP controller behavior
- infra implementation ownership such as JDBC or in-memory storage backends

## Current Boundaries

- `xa-mass-sdk-api` is the contract artifact
- `xa-mass-sdk` is the embedding/runtime-composition artifact
- infra modules must not export `com.xa.mass.sdk.*` ownership back out of this
  module family

## Start Here

- `src/main/java/com/xa/mass/sdk/auth`
- `src/main/java/com/xa/mass/sdk/catalog`
- `src/main/java/com/xa/mass/sdk/event`
- `src/main/java/com/xa/mass/sdk/model`
