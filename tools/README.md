# Tools Directory

Status: current local/admin tools map.

This directory owns executable tooling that operates against a running server
but is not an SDK product surface.

## Module Map

| Module | Artifact | Role |
| --- | --- | --- |
| [`xa-mass-admin-cli`](./xa-mass-admin-cli/README.md) | `xa-mass-admin-cli` | server-owned operator/admin HTTP CLI for health, auth, API-key inspection, and typed env init |

## Boundaries

- Tools may automate operator/admin HTTP flows over a running server.
- Tools must not become public task/worker actor SDKs.
- Tools must not depend on `sdk/xa-mass-java-sdk`.
- Environment initialization belongs here, not in `xa-mass-java-sdk` and not
  in server startup seed paths.

## Verification

```bash
./mvnw -pl tools/xa-mass-admin-cli -am test
```
