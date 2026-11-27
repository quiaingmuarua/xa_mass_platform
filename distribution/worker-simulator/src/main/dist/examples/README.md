# Deployed Worker consumer examples

These are templates, not provisioned environments or passing proof records. Use
the installed kit from the selected Release. The Runtime operator supplies the
registered Groups, installed event declarations, enabled Matching resources and
Project grants. Neither example creates those resources or changes the Server.

## Persistent ordinary Host

`persistent-20-host.json` uses the existing Simulator configuration to retain 20
MD5-capable Workers in one Group. Replace the Worker origin and Group before use.
Copy the configuration to `/etc/xa-mass-worker/persistent-20-host.json` and provide
the gateway credential at `/etc/xa-mass-worker/private/worker-access.json`. This
private file has the Host's existing `keyId` / `secret` shape; it is not shipped.
The gateway path resolves against the configuration directory.

`xa-mass-worker-host.service` assumes an operator-created `xa-mass-worker` system
user, a verified kit at `/opt/xa-mass-worker-simulator/current`, and a writable
`/var/lib/xa-mass-worker` directory. Install Java 21, make the private file readable
only by the service user, and install the unit under `/etc/systemd/system/` after
adjusting these paths. The unit starts only the ordinary Host, restarts its failed
process and closes its own process group on stop. Its heap and FD values are
explicit process ceilings, not allocations or minimum requirements.

The Host's control listener remains loopback-only. Retain the configured sandbox
across service and Release restarts so that the same inventory coordinates are
reused; Server-issued IDs also depend on the Runtime retaining its identity state.
The example uses `/var/lib/xa-mass-worker/persistent-20/data/scenario-workers`:
the final `data/scenario-workers` segments are required by the Host's owned-root
validation. A parent such as `/var/lib/xa-mass-worker/persistent-20` is not a valid
`sandboxRoot`, even when its permissions allow writes.
Credential changes take effect after an explicit Host restart. Keep the inventory,
credential and logs outside the immutable kit.
The template also configures an explicit bounded `reconnectPolicy` (`60` attempts
at `2000ms` interval, `10000ms` stability window — roughly 120 seconds) so ordinary
Server restarts do not exhaust the default 10-second SDK retry budget; this remains
bounded rather than an unlimited reconnection guarantee.

This service needs no operator access key. It schedules no Tasks, judges no health
and installs no timer. A running process or local `RUNNING` snapshot does not prove
that the Runtime can execute work. The kit's finite round remains an explicit
caller action.

## On-demand call performance

`performance-deployment.json` supplies the existing Performance runner's
`--deployment-config`. It contains references to two private files, not credentials.
Copy it outside the kit and replace every deployment coordinate, including the
`0.0.0` / zero-commit `serverDeployment` template, with the operator's recorded
Server version, full commit and configuration identity. Relative file paths resolve
against this configuration's directory, independently of the shell directory.

Use two distinct Group IDs with existing Any supply/query and MD5 declarations,
an access key granted to the named Project, and `rpcTaskIds` referencing the two
administrator-provisioned RPC channels (`POST /api/v1/tasks/rpc` with `ownerUserId`
set to that operator user). The deployed runner only validates those pre-provisioned
channels via read-only `GET`; it never creates RPC Tasks. The recorded assignment ceiling
uses the production default `100` in the template; replace it if the recorded
Server configuration differs. The runner does not change it. Keep `consumerId` stable
for the same Project/Group bindings.

From the extracted kit:

```sh
python3 acceptance/performance/run_worker_call_performance.py \
  --deployment-config /etc/xa-mass-worker/performance-deployment.json \
  --lane-modes open --lane-attribution off --diagnostics off \
  --output-root /var/lib/xa-mass-worker/runs/open-001
```

The output directory must be new, private and outside the kit. This starts the
runner's own Host and Harness and uses the original open rates `500,1000,2000`,
warmup and windows. A rate subset may use the existing `--lane-rates` option.
It starts no Server or Redis and requires no Docker, Server SSH, JFR or remote
Redis access. It cleans up only the local processes it started; RPC Tasks remain
for later reuse. Publish only the runner's safe evidence, not raw logs, inventories,
private configuration or key files.

Use separate Group and inventory coordinates from the persistent Host. Different
local directories do not isolate identical Group/inventory identities. These two
roles may share a physical client, but contend for its resources, source ports and
network path; the persistent Host is not an independent external witness. The
Performance result describes its recorded public path and client configuration,
not maximum Server throughput, recovery, saturation or long-term health.
