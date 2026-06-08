# Server Control-Plane Schema

Status: current schema catalog.

This directory documents the current table shape for server-owned
control-plane stores:

- API-key applications
- API-key lifecycle records
- operator IAM users, roles, permissions, and bindings
- operator password credential lifecycle
- low-volume API usage ledger rows
- worker registration observation rows

It is not a historical migration ledger. During the current pre-release stage,
schema changes may rewrite the current table design and require deleting or
recreating local/prod databases.

## Seed And Import Boundary

Server seed/import is for control-plane bootstrap only. It may initialize stable
configuration and credential bootstrap material, but it must not create runtime
truth or replace SDK/API actor behavior.

| Data | Seed/import stance | Notes |
| --- | --- | --- |
| projects and event catalog | allowed | Server-owned control-plane configuration. |
| rules and policy configuration | allowed | Seeded config may influence later runtime resolution; resolved policy snapshots are not seed data. |
| operator credentials | allowed | Hash material only; password hashes stay in `xa_operator_credential`. |
| API keys | allowed with secret-source controls | Raw secret is import material only. Durable truth is hash, prefix, scopes, permissions, owner, status, expiry, and metadata. |
| task shells and task items | forbidden | Use task producer SDK/API calls. |
| seal, approve, pause, resume, cancel | forbidden | Use operator/server-control APIs. |
| WorkerGroup, AdapterNode, NodeGroupBinding, Worker | forbidden today | Use worker API or Java SDK registration. If WorkerGroup becomes seedable later, define a control-plane owner and runtime projection first. |
| worker state, capability reports, heartbeat, presence | forbidden | These are external actor/runtime evidence. |
| ready/delayed queues, leases, assignments, reservations, occupancy, route buckets, dispatch gates, final result rows, command status | forbidden | These belong to runtime owners, not server seed/import. |
| usage, trace, audit rows, sessions, CSRF tokens | forbidden | Generated evidence must come from real operations. Seed/import itself may emit audit evidence; it must not seed audit evidence. |

Profiles may choose memory/local or persistent/fail-closed infrastructure,
seed source, local fixture convenience, and sample secret policy. They must not
change this taxonomy or turn local shortcuts into alternate public API or
authentication behavior.

Memory runtime is not a seed/import bypass and not a lower-trust product mode.
When memory infra is used for immediate or RPC-style deployments, API-key,
operator auth, seedability, worker registration, and runtime-truth boundaries
remain the same.
Trusted-auth startup and test proof may use memory or persistent infra; the
trust requirement is session/CSRF for operator mutations plus API keys for SDK
and worker-api calls, not a specific runtime backend.

## Current Tables

Executable DDL lives under
`../migration/server-control-plane`.

### `xa_api_key_application`

Owner: `ApiKeyApplicationStore`.

Purpose: durable API-key application/review workflow.

Main shape:

- `application_id` primary key
- applicant and requested principal/user columns
- requested project/event scopes and permissions as JSON text
- application status, review reason, reviewer, created/reviewed timestamps
- attributes as JSON text

### `xa_api_key_credential`

Owner: `ApiKeyCredentialStore`.

Purpose: durable API-key lifecycle truth used by validation, list/revoke,
expiry, and UI. This table is not the raw authentication projection; the
auth projection remains in `xa_principal` through
`CredentialAuthProjectionWriter`.

Main shape:

- `key_id` primary key
- `principal_id` unique key linked to auth projection identity
- created-for user, key prefix, credential hash
- `project_scope_mode` plus `project_scopes_json`
- `event_scope_mode` plus `event_scopes_json`
- permissions and attributes as JSON text
- lifecycle status, application id, created/revoked metadata, expiry

Scope mode values must distinguish omitted, wildcard, and bounded scope
semantics at schema level even while the current Java read model still exposes
project/event scopes as lists.

### `xa_iam_user`

Owner: `UserRolePermissionStore`.

Purpose: durable operator user truth.

Main shape:

- `user_id` primary key
- display name, email, status
- attributes as JSON text
- created/updated timestamps

### `xa_iam_role`

Owner: `UserRolePermissionStore`.

Purpose: durable operator role and permission set truth.

Main shape:

- `role_id` primary key
- name, description
- permissions as JSON text
- `system_role` flag
- updated timestamp

### `xa_iam_user_role`

Owner: `UserRolePermissionStore`.

Purpose: durable operator user-to-role bindings.

Main shape:

- `(user_id, role_id)` primary key
- grant metadata: `granted_by`, `granted_at`

Built-in operator IAM defaults are seed-if-missing only. They must not
overwrite existing operator rows.

### `xa_operator_credential`

Owner: `OperatorCredentialStore`.

Purpose: durable operator password credential lifecycle for server-owned
operator session login. This table is separate from IAM profile / role /
permission truth and password hashes must not be stored in
`UserRecord.attributes`.

Main shape:

- `user_id` primary key linked to operator IAM user identity
- `password_hash` containing the encoded password hash
- optional `hash_algorithm` when the encoded hash does not carry algorithm
  metadata
- credential status and created/updated timestamps

### `xa_api_usage_ledger`

Owner: `ApiUsageLedgerStore`.

Purpose: low-volume, inspectable API usage evidence for API-key operations.
This is not runtime, trace, or high-volume dispatch history.

Main shape:

- `usage_id` primary key for append idempotency
- key/principal/user identity columns
- project/event/operation/task/message/request dimensions
- units and accepted/rejected/failed-after-accept status
- optional failure reason/status
- created timestamp

### `xa_worker_registration_observation`

Owner: `WorkerRegistrationObservationStore`.

Purpose: append-only, low-volume observation output for successful public
worker registration ingress. This table is for audit and future analysis only;
it must not restore workers, presence, heartbeat, transport sessions, dispatch
routing, or scheduling candidates.

Main shape:

- `observation_id` primary key
- `resource_type`, `resource_id`, and `action` for the accepted registration
  event
- authenticated principal id/type
- `request_hash` for the stored bounded payload representation
- bounded `payload_json` for selected registration facts
- `occurred_at` timestamp

Recorded events are WorkerGroup declaration, AdapterNode registration,
NodeGroupBinding, and Worker registration after the runtime owner accepts the
operation. Heartbeat, online/offline, poll, result, command delivery,
capability report, and state report are not worker registration observation
truth in the current schema.

When adding schema notes or DDL baselines here:

- keep API-key lifecycle truth separate from the auth projection in
  `xa_principal`
- represent project/event scopes with explicit omitted, wildcard, and bounded
  modes before persisting them
- keep API-key viewer sessions out of SQLite/JDBC
- keep worker registration observation rows out of runtime restore,
  scheduling, matching, presence, heartbeat, and transport routing
- avoid runtime queues, leases, worker presence, dispatch streams, trace
  events, and high-volume history
- prefer portable SQL shapes that can be validated on SQLite now and adapted to
  PostgreSQL later
