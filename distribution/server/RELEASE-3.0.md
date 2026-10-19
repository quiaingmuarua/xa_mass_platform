# XA Mass 3.0 release and upgrade contract

Status: 3.0 candidate public contract; this document does not announce a release.

## Public compatibility

The Runtime and Worker SDK share one explicit version, complete Git commit and
exact ZIP hashes. Candidate archives use `3.0.0`; ordinary development uses
`3.0.0-SNAPSHOT`. Runtime API routes remain `/api/v1`, Runtime manifest schema is
6 and SDK manifest schema is 1. Java 21, external Redis, the four SDK artifacts,
Java 11 portable contracts and Android minSdk 24 retain their meanings.

3.0 deliberately changes public startup configuration. The `agentforge` Boot
profile is removed and replaced by `consumer`, without an alias. The manifest
lists `default`, `consumer`, `preview`. Adapter ID changes from
`agentforge-websocket` to `runtime-websocket`; use standard `SERVER_PORT` and
`XA_MASS_ADAPTER_PORT` instead of `XA_MASS_AGENTFORGE_SERVER_PORT` and
`XA_MASS_AGENTFORGE_ADAPTER_PORT`. The new default scope is `profile_consumer`.
These incompatible startup changes require a major version. Compatible 3.x
additions and fixes use minor/patch versions; future public incompatibility
requires another major version. Historical 2.0 binaries and their contract remain
version-scoped and immutable.

## External consumer startup

`consumer` is the clean external-consumption preset, not a consumer-specific
business module or transport-only naming scheme. Its DEFAULT Pacer, Server and
WebSocket Adapter start with no Project/Group/Task seeds. The deployment explicitly
declares Project `default` and supplies its own Group JSON through
`xa.mass.worker-assembly.group-config-json`. Other Projects can still be declared
with explicit bindings. Neither declaration nor registration creates a Task or
a Matching Pool. The caller explicitly prepares its stable RPC Task before calls.
Development identity/access requirements are unchanged and are not session auth.

[XA Android](../../xa-android/README.md) is the standalone reference: it consumes
both verified ZIPs, builds outside the platform checkout from the SDK repository,
starts the extracted JAR/frontend and verifies an actual Android digest call.
The normal entry never changes freezer settings or cleans Redis; the separately
owned proof controls its disposable API 33 environment and exact `test_*` scopes.

## Proof and promotion

One clean commit produces one Runtime/SDK ZIP pair. Runtime Distribution and the
complete Android Proof consume those exact bytes. APK provenance records the
actually resolved SDK hashes; phase evidence records identity and assertions,
not opaque Worker payloads/results. Full manual Proof CI must succeed before
promotion. Published release assets remain immutable; this slice creates no tag
or Release and does not replace AgentForge's existing lock or installation.

AgentForge must adapt profile/Adapter/environment validation before upgrading its
lock, then perform its own business consumption proof. The XA reference proves
the public path, not downstream target Apps, accounts, hooks or business behavior.
Use a separate installation and explicit new deployment scope; do not import
historical private storage or apply consumer profile renames to old binaries.
