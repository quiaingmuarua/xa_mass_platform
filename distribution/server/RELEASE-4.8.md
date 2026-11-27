# XA Mass 4.8 release and upgrade contract

Status: current 4.8 release input. 4.8.0 is pending publication and retained-scope
GCP upgrade. Actual host installation state belongs to the host records reached
through the [Release Preview guide](../../doc/guide/operations/deploy.md#release-preview).

## Release preparation

`.github/scripts/prepare_release.py` reads this file for exact `v4.8.<patch>`
tags. Each minor requires its own contract; promotion has no major-version
fallback. A candidate uses its exact version and merged commit in complete
manual Proof, including SMS Reception Preview, then promotes those same three
Runtime/SDK/Simulator ZIPs and their three SHA256 sidecars. Manifest schemas are
6/1/3. Published candidates are not relabeled or rebuilt on GCP. Publication and
deployment are separate recorded facts.

## 4.8.0 Server-minted RPC channel provisioning, admin close and purge-only retention

4.8.0 delivers:

- **Server-minted admin-only RPC channel provisioning**: retired
  `POST /api/v1/tasks/{taskId}/rpc:prepare` (no compatibility alias) and replaced
  it with admin-only `POST /api/v1/tasks/rpc` (`TaskRpcPrepareRequest` with
  optional `ownerUserId`). Redis-backed `TaskRpcChannelRegistry`
  (`xa_mass:<scope>:server:rpc-channel:<sha256>`) mints `rpc-<32-hex>` per active
  `(projectId, ownerUserId, workerGroupId)` slot with idempotent reuse on
  identical config, `400/12006` on config mismatch, `403/12013` for non-admin
  callers, and JVM-only `prepareServiceChannel` for internal Scenarios (`#155`).
- **Admin close and purge-only retention for raw RPC channels**: narrowed Kernel
  `RedisTaskRuntime.RETENTION_SCRIPT` so `freeze`/`release` stay
  `CLOSE_WHEN_IDLE`-only while `begin`/`purge`/`finish` also reclaim `TERMINAL`
  `PARK_WHEN_IDLE` Tasks under the negative terminal Score fence. Extended
  `TaskLifecycleService.close(...)` for admin-only closure of raw `PARK_WHEN_IDLE`
  RPC channels (`403/12013` for operator, `400/12008` for internal Scenario
  channels), immediately releasing the `TaskRpcChannelRegistry` slot and evicting
  cached call descriptors, with purge-only Redis cleanup after
  `xa.mass.task-retention.grace` (or immediately via admin
  `POST /api/v1/tasks/{taskId}/retention:run`) and zero rows written to
  `task-history.sqlite` (`#156`).
- **RPC Workbench & SPA convergence**: updated Console RPC workbench for
  server-minted channel creation, admin-only `"关闭通道"` confirmation, automatic
  post-create redirect to the RPC list, same-session re-creation after close, and
  task-list refresh on `view=create` (`#155`, `#156`, `#157`).
- **Ingress, OpenAPI, persistence & Host example upkeep**: updated
  `staging-ingress/renderer.py` to expose `POST /api/v1/tasks/rpc` and retire
  `/rpc:prepare`, updated the verified OpenAPI snapshot and deployed
  `worker-call-performance` consumer contract (`rpcTaskIds`), extracted
  `UserDirectoryStore` from `UserDirectory` without schema change, and set a
  bounded 120s `reconnectPolicy` in `persistent-20-host.json` (`#153`, `#154`,
  `#155`, `#156`).

### Retained-scope upgrade constraints from 4.7.0

Upgrading an existing 4.7.0 Release Preview Runtime to 4.8.0 reuses the existing
Redis scope (`profile_long_preview_4_4`), SQLite finite Task history database
(`/var/lib/xa-mass/task-history-4.4.sqlite`, schema 1), SQLite control-plane user
directory (`/var/lib/xa-mass/control-plane.sqlite`, schema 1, WAL), credit
accounts, Task/request identities, business configuration, gateway credentials
and standing Worker Host (`4.4.2`) / Performance kit (`4.4.4`). It requires no
Redis scope reset, SQLite schema migration or Worker Host/SDK replacement.

Apply these 4.8.0-specific host updates during the stopped-Runtime maintenance
window:

1. **Re-render Nginx staging ingress from the 4.8.0 Runtime ZIP**: render
   `/etc/nginx/conf.d/...` using `examples/staging-ingress/renderer.py` from the
   immutable 4.8.0 Runtime archive while preserving existing TLS certificates,
   gateway credentials and loopback upstreams so public HTTPS admits
   `POST /api/v1/tasks/rpc` and stops exposing the retired
   `POST /api/v1/tasks/{taskId}/rpc:prepare` route. Verify `nginx -t` and reload
   Nginx.
2. **Pre-existing raw RPC channels on retained scope**: existing running raw RPC
   Tasks created before 4.8.0 remain observable, callable via `items:call` and
   closable by admin via `POST /api/v1/tasks/{taskId}/close`. New raw RPC
   channels are provisioned exclusively by admin via `POST /api/v1/tasks/rpc`
   (minted `rpc-<32-hex>`).
3. **Post-restart standing Worker check**: verify the standing 20 Workers
   (`persistent-workers`, with 120s bounded `reconnectPolicy`) automatically
   reconverge to `20/20 activeHot`; if any remain `STOPPED`, resume explicitly
   via the existing Ali Lab start procedure and record manual resume separately.
4. **Runtime, Ingress, SQLite & Console acceptance**: verify
   `/actuator/health/readiness`, loopback `/actuator/prometheus` (`xa_mass_*`
   series), `/var/lib/xa-mass/control-plane.sqlite` and
   `/var/lib/xa-mass/task-history-4.4.sqlite` (`PRAGMA quick_check=ok`), public
   `POST /api/v1/tasks/{id}/rpc:prepare -> 404`, admin `POST /api/v1/tasks/rpc`
   -> `items:call` (3-Item UTF-8 MD5) -> `POST /api/v1/tasks/{id}/close` ->
   immediate slot re-mint -> `retention:run` purge (0 SQLite history rows added),
   a fresh 3-Item finite MD5 Task with JSONL export, interactive authenticated
   Console verification on `https://staging.xamass.net`, and 0
   `xa-mass.service` warnings/errors.

## Finite Task history and RPC retention

4.8 preserves immutable SQLite finite Task history (schema 1) and opt-in
terminal RPC Item retention without schema changes, and adds purge-only Redis
retention for admin-closed raw `PARK_WHEN_IDLE` RPC channels (zero SQLite
history writes). See
[Server retention and history Owner](../../server_jvm/README.md#finite-task-retention),
[Server RPC retention Owner](../../server_jvm/README.md#rpc-terminal-item-retention)
and [Boot configuration](../../server_boot_jvm/README.md#finite-task-retention-configuration).

## Published Worker consumers

The Release retains Runtime, Worker SDK and Worker Simulator kit ZIP components
and SHA256 sidecars. Runtime and SDK manifest schemas are 6 and 1; Simulator kit
schema 3 includes
`performanceEntry=acceptance/performance/run_worker_call_performance.py` and
compiled libraries under `acceptance/performance/harness/`.

Each immutable kit and target Runtime is pinned independently by exact version,
commit and checksum for deployed Performance. The two commits need not match;
neither coordinate uses `latest` or a fallback. This is not a general
cross-version compatibility promise. Existing APKs are not replaced
automatically, and prior-version witnesses retain their versions.

The [Worker Call Performance Owner](../../integrations/worker-call-performance/README.md#deployed-runtime-consumer)
owns deployed workload and measurement limits. Current kit usage and
configuration belong to the
[kit guide](../worker-simulator/src/main/dist/README.md) and
[kit examples](../worker-simulator/src/main/dist/examples/README.md).

## Earlier release records

Completed 4.7, 4.6, 4.5, 4.4 and earlier historical records remain immutable:

| Version | Record |
| --- | --- |
| 4.7.x | [4.7 release and upgrade contract](RELEASE-4.7.md) |
| 4.6.x | [4.6 release and upgrade contract](RELEASE-4.6.md) |
| 4.5.x | [4.5 release and upgrade contract](RELEASE-4.5.md) |
| 4.4.x | [4.4 release and upgrade contract](RELEASE-4.4.md) |
| Capability evidence | [Dated external public closeout](../../doc/guide/capabilities.md#external-public-closeout) |
