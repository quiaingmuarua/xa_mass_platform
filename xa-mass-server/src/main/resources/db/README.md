# Server DB Resources

Status: current server-owned DB resource boundary.

`xa-mass-server` owns server product/control-plane schema for API-key
lifecycle, IAM/user-role state, and low-volume API usage evidence.

Use these directories:

- `schema/server-control-plane/`: current server table-shape notes and DDL
  baselines for humans and agents.
- `migration/server-control-plane/`: executable Flyway SQL for the current
  server-owned control-plane schema generation.

Do not put server API-key, IAM, usage, or submitter-viewer session schema under
`platform_infra/mass-storage-jdbc`. That module owns generic storage schema
such as task shell, rule storage, and generic principal projection support.

Current project stage:

- historical DB upgrade compatibility is not promised
- schema changes may require deleting/recreating local/prod DBs
- tests should prove clean DB creation and restart durability for the current
  schema
- SQLite is the current lightweight control-plane DB target, but schema should
  stay portable enough for a later PostgreSQL path

Submitter-viewer sessions are not server control-plane DB truth. They remain
memory-only unless a future runtime/Redis session design explicitly changes
that owner.
