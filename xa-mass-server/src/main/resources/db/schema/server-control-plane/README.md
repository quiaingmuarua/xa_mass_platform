# Server Control-Plane Schema

Status: current schema catalog.

This directory documents the current table shape for server-owned
control-plane stores:

- API-key applications
- API-key lifecycle records
- operator IAM users, roles, permissions, and bindings
- low-volume API usage ledger rows

It is not a historical migration ledger. During the current pre-release stage,
schema changes may rewrite the current table design and require deleting or
recreating local/prod databases.

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

When adding schema notes or DDL baselines here:

- keep API-key lifecycle truth separate from the auth projection in
  `xa_principal`
- represent project/event scopes with explicit omitted, wildcard, and bounded
  modes before persisting them
- keep submitter-viewer sessions out of SQLite/JDBC
- avoid runtime queues, leases, worker presence, dispatch streams, trace
  events, and high-volume history
- prefer portable SQL shapes that can be validated on SQLite now and adapted to
  PostgreSQL later
