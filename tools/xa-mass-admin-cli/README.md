# xa-mass-admin-cli

Status: server admin CLI.

`xa-mass-admin-cli` is the operator/admin automation entry for a running
`xa-mass-server`.

## Current Scope

- `health`: verify the server health endpoint.
- `auth config`: inspect operator auth mode.
- `auth login`: perform session operator login and CSRF capture.
- `api health`: measure selected repeatable local read routes and emit a
  machine-readable route timing report.
- `api-key current`: inspect an API-key principal through
  `/api/v1/api-keys:current`.
- `env verify`: verify required catalog/rule/API-key facts from typed config.
- `env init`: apply catalog/rule/API-key desired state from typed config,
  verify it, and write an optional local marker after successful verification.

## Boundary

- This module calls server HTTP APIs. It does not write DBs directly.
- This module must not depend on `xa-mass-java-sdk`, `xa-mass-server`,
  `xa-mass-engine`, transport implementations, or runtime/storage
  implementations.
- `env-init.json` is a local checkpoint only. It is never server truth and
  never replaces verification.
- Dev-header/fixture auth is not the process confidence path. Real env init
  requires session operator auth and an active login-capable operator
  credential.

## Usage

```bash
./mvnw -pl tools/xa-mass-admin-cli -am -DskipTests package

java -jar tools/xa-mass-admin-cli/target/xa-mass-admin-cli.jar \
  env init --config tools/xa-mass-admin-cli/examples/admin-env.local.json

java -jar tools/xa-mass-admin-cli/target/xa-mass-admin-cli.jar \
  api health --config tools/xa-mass-admin-cli/examples/admin-env.local.json
```

The example config uses:

- checked-in catalog/rules manifests
- a one-worker confidence worker spec
- gitignored `examples/secrets/` for generated API-key material
- gitignored `examples/.state/env-init.json` as a local marker

`api health` uses the same typed config and session operator flow as `env init`.
It records `routeTimings` with route auth policy, credential used by the
health runner, HTTP status, envelope code, response bytes, and elapsed
milliseconds. It is a reachability and local latency gate; exact DTO shape
belongs to API contract and adapter tests.

## Verification

```bash
./mvnw -pl tools/xa-mass-admin-cli -am test
```
