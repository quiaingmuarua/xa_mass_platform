# Server Control-Plane Migrations

Status: current executable migration location.

Executable SQL for server-owned API-key, IAM, and API usage stores belongs in
this directory. `xa-mass-server` must own the Flyway runner or equivalent
migration execution that loads this location against the configured JDBC
`DataSource`.

Expected Flyway location:

```text
classpath:db/migration/server-control-plane
```

Rules:

- do not add server API-key/IAM/usage tables under
  `platform_infra/mass-storage-jdbc`
- do not add submitter-viewer session tables
- do not add runtime queue, lease, worker presence, dispatch, result
  convergence, trace, or high-volume history tables
- current pre-release schema changes may require deleting/recreating the DB
- tests should cover clean DB creation and current-schema restart behavior, not
  historical upgrade compatibility

Current executable migrations:

```text
V1__server_api_key_lifecycle.sql
V2__server_operator_iam.sql
V3__server_api_usage_ledger.sql
V4__server_operator_credentials.sql
V5__server_worker_registration_observation.sql
```
