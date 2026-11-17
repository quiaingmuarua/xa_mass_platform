# XA Mass 4.3 release and upgrade contract

Status: version-scoped 4.3 contract. Publication requires the complete manual Proof at
the exact candidate commit. The current patch target is 4.3.2. Published 4.2, 4.3.0
and 4.3.1 artifacts remain immutable.

## 4.3.2 patch: Batch Checks browser callers

On 2026-11-17 the existing GCP 4.3.1 deployment enabled Batch Checks and Messages
through external configuration. Their small API execution checks passed, and Messages
browser use passed, but the Batch Checks task list failed after a valid login. Four
Batch Checks components passed the display-only `ConsoleUser` profile into Scenario
source operations. That profile has no access key, so `callerHeaders` rejected the
request locally before HTTP. Catalog loading still worked through the current session
key. Reloading or logging in again could not repair the incorrect caller handoff.

Direct API acceptance supplied a valid Bearer key and did not exercise this component
path. Existing component tests used Mock sources that checked user/role access without
requiring HTTP caller headers; direct API-source tests supplied a complete caller.
Those checks therefore missed the component-to-API boundary failure.

4.3.2 captures the immutable `TaskCaller` in the Batch Checks workspace, detail drawer,
configuration editor and submission recovery. The separate profile still owns display,
permission checks and login-change comparisons. Regression tests drive those actual
components through `ApiAppCheckTaskSource`, including configuration saves and a delayed
read across a login change. The original caller key remains non-enumerable and is not
serialized or replaced with the later login's key.

This patch changes no Server API, Worker protocol, cooldown behavior, business pricing,
configuration format or stored data. Upgrade the current 4.3.1 deployment using the
matching 4.3.2 Runtime and Simulator kit while retaining its enabled Batch Checks and
Messages configuration, Group/resource bindings, scope, users, credits and archives.
The pure-platform disable example below belongs only to installations that choose that
composition; it is not an instruction to disable the currently enabled GCP Scenarios.
Complete the 4.3.2 browser acceptance below before closing this deployment. The earlier
API success and local frontend checks do not substitute for that acceptance.

## 4.3.1 patch: cooldown confirmation dialog

The actual 4.3.0 GCP browser acceptance found that the connection-control component
used `ElDialog` without importing it. The application's component registration did
not supply it, so the cooldown confirmation did not render. The earlier component
test registered the dialog globally and therefore missed this shipped-page defect.
4.3.0's complete manual Proof passed, but its required browser acceptance failed;
publication and API/Worker success must not be recorded as a completed deployment
acceptance.

4.3.1 imports the dialog in the owning component and tests the confirmation with the
application's actual registration boundary. The patch changes no Worker wire shape,
SDK connection behavior, Server API, configuration or stored data relative to 4.3.0.
The 4.3.0 and 4.3.1 SDKs use the same protocol. Release acceptance still consumes the
matching 4.3.1 Runtime and Simulator kit so its artifact provenance is unambiguous.
Do not modify or overwrite the published 4.3.0 ZIPs to apply this fix.

For an installation still on, or restored to, 4.2, apply the complete 4.2-to-4.3
configuration and client steps below. An installation on 4.3.0 can keep its scope,
users, configuration, identities and archives and replace only the published release
artifacts. In either case repeat the real cooldown confirmation, refusal, release and
recovered finite round below before declaring 4.3.1 accepted. The 4.3.0 failure remains
historical evidence even after the patch passes.

This minor release includes Worker connection cooldown, consumer business defaults,
the current external-load closeout and the report-branch deployment repair. The
Runtime, Worker SDK and Worker Simulator kit remain three ZIP components with SHA256
sidecars; their manifest schemas remain 6, 1 and 2 respectively.

## Worker compatibility

Worker Prepare now returns a tagged `prepared` or `cooldown` result. A prepared result
includes `status`, Worker ID, Transport and Endpoint; a cooldown result includes
`status`, Worker ID and the absolute cooldown deadline, without an Endpoint. Batch
Prepare can contain both outcomes. Terminal connection notifications can carry the
structured cooldown reason.

The strict 4.2 SDK cannot consume the 4.3 Prepare response, and the 4.3 SDK cannot
consume the old response. Upgrade external Java Hosts to the 4.3 Simulator kit and
rebuild Android Apps against the 4.3 SDK before reconnecting them. Do not treat an
existing 3.2/4.2 APK or historical Android public witness as 4.3 acceptance. Installed
Apps can remain stopped until their own upgrade; this deployment does not replace
devices automatically. Keep retained inventory and clientWorkerKey values when
upgrading the same Host/App against the same Runtime scope.

Server owns the administrator's current cooldown record. Each WebSocket identity
admission checks it, and setting cooldown revokes the current local route. This is
separate from scheduling PAUSE and Pool eligibility. Busy Handlers can finish with
their original run; disconnect does not undo business effects or prevent the existing
Task retry mechanism. Java stops only the affected Worker instance. Android's recovery
is a process-local low-frequency check, not a background keepalive service.

## Consumer configuration

`consumer` now enables Batch Checks and Messages by default. Each enabled Scenario
requires complete external business and resource configuration. SMS remains in the
existing Preview assembly. `default` remains pure platform; Preview keeps its existing
demo composition.

The original 4.2-to-4.3 GCP transition retained a pure-platform consumer. Only an
installation intentionally retaining that composition should add these explicit values
to its external Runtime YAML before switching binaries:

```yaml
xa:
  mass:
    scenarios:
      app-checks:
        enabled: false
      messages:
        enabled: false
```

Merge these keys into the existing YAML object, retaining the user-directory location,
Group registrations, Projects, Pool/query bindings, endpoint addresses and other
settings. No business configuration file, Scenario resource or task is implicitly
created by this upgrade. Deployments that intentionally use both businesses instead
provide their valid external configuration, as shown in the packaged
`examples/consumer-business` files. The current GCP 4.3.1 configuration already enables
both businesses; preserve those settings and external files during the 4.3.2 upgrade.

## State and GCP upgrade

Kernel Task/Item/Result data, Worker identities, users, credit records and archives keep
their existing formats. Reuse the current scope, users file, archive directory and
daily credentials; no identity migration, reset or Redis cleanup is required. The 4.3
Server-owned `server:worker-connection-control:<groupId>` HASH under the existing scope
is absent until an administrator writes a decision. Missing records allow admission;
unreadable records or a failed store read do not.

Use the [Release Preview operating procedure](../../doc/guide/operations/deploy.md#release-preview):

1. Verify the published Runtime ZIP against its checksum, GitHub asset digest and
   manifest commit. Install it in its own immutable version directory. Record the
   operation start and take the single upgrade restore point covering configuration,
   users, ingress, Redis and archives, preserving ownership and permissions.
2. Stop any old external Host that would otherwise reconnect with an incompatible
   SDK. Prepare the matching 4.3.2 kit on the external acceptance machine; no Worker Host
   or build runs on GCP.
3. Preserve the deployment's chosen Scenario composition and its external files. The
   current GCP upgrade retains both enabled businesses; use the disable example only
   for a deliberately pure-platform consumer. The first 4.2-to-4.3 transition also
   renders Nginx with the new Runtime's renderer to add administrator
   `GET/PUT .../connection-control` routes. A 4.3.1-to-4.3.2 upgrade needs no new ingress
   route. Preserve TLS, Worker allowlists, credentials and capacity settings; validate
   any rendered changes with the documented Nginx service user before loading them.
   Worker ingress must still reject management routes.
4. Stop the old Runtime, switch the exact JAR and installation link, and start one
   Runtime/Pacer against the retained scope. Check readiness and the running artifact
   coordinate. No in-place edits to an extracted JAR or frontend are permitted.
5. Complete the small acceptance below. Record each executed result and any untested
   client separately. Update the external-consumer Environment's deployed version and
   commit to the installed Release, without launching a capacity campaign.
6. Revoke temporary acceptance keys, stop the acceptance Host, and remove its temporary
   secrets. Keep daily keys and the retained data. After acceptance, remove the old
   Release directory and single restore point, then finish the operation record.

On a failed switch, close public business ingress and use the documented restore point
and previous binary. Do not clear Redis or replay uncertain tasks. A rollback to 4.2
does not enforce 4.3 cooldown records, so binary rollback across that boundary cannot
preserve a newly applied cooldown policy. Resolve that during the bounded upgrade
window before reopening ingress; never report 4.2 as enforcing the new control.
A 4.3.2 rollback to 4.3.1 retains the same protocol and configuration formats, together
with the known Batch Checks browser caller defect.

## Acceptance focus

Complete manual Proof supplies the existing Owner, Redis, Runtime, Android, distribution
and business regression evidence at the release commit. GCP only checks the deployment
with a small workload:

- Public Console loads; unauthenticated API requests fail; a valid access key can read
  the deployment. Confirm the retained Batch Checks and Messages Catalogs and bindings.
- Start two external Java Workers from the published 4.3.2 kit. Run its shipped finite
  round through Any Pool, independently checking Item identity/state, MD5 Results,
  terminal Task and complete export.
- In the actual Batch Checks browser page, read the task list, a known task's detail
  and the saved/effective configuration. Create a tiny MD5 task, import its seeds,
  confirm the real credit quote and paid approval, then inspect its terminal results
  and download the CSV. Independently compare every digest and verdict with the inputs
  and verify the recorded debit. Preserve the original request identity if any write
  response is uncertain; do not create or charge a replacement task automatically.
- Confirm Messages remains available with its retained application/Group binding and
  existing task observations. A small send may exercise the same local simulated
  channel; record SENT and any separately exercised later observations accurately.
  This patch does not change Messages or prove third-party delivery.
- Preserve historical Android, browser and capacity records with their original
  versions and scope. Mark any unexecuted current-version flow as such. A 4.2 APK is
  not expected to reconnect until upgraded to the 4.3 SDK.

The 4.3.2 UI caller fix does not require repeating the already accepted cooldown
exercise. For a first upgrade from 4.2, or the historical 4.3.1 dialog-fix acceptance,
the affected admission exercise is:

- In Runtime Workers, use the administrator connection-control UI on one acceptance
  Worker. Set a future cooldown, observe its disconnect and refusal on explicit start,
  then release it and explicitly start it again. Confirm the second Worker stayed
  usable. After recovery, run another small finite round. Retain screenshot/API/Host
  evidence for the UI and actual admission outcome; an API-only run does not prove the
  browser operation.

Use the standing acceptance user and a temporary `acceptance-4.3.2` Worker key following
the host's existing private-file procedure. Read current control revisions before any
requested control change and retain a request identity on an unconfirmed response;
never automatically retry a mutation with a new identity. If the cooldown exercise is
performed, finish with the test Worker's cooldown released.

The prior direct-public 1,000/999-Worker acceptance remains a dated 4.2 result. The full
direct 15k/10k target remains unconfirmed, and the SSH diagnostic result is not direct
public acceptance. This Release does not reopen that investigation or require another
capacity run. See the [public closeout](../../doc/guide/capabilities.md#external-public-closeout).

## Report branch deployment repair

The nightly performance report branch stores data rather than the frontend application.
Its publisher now maintains a `frontend/vercel.json` deployment-disable marker, so a
report push does not request a Vercel application build against the absent source tree.
The existing report branch receives the same marker without replacing report data.
Normal application branch deployments and nightly workloads, scales, assertions and
time limits remain unchanged. Historical failed Preview deployments remain historical;
the verification is that a subsequent report commit no longer creates that failure.

## Publication

Run complete manual Proof on the exact merged candidate, then tag that commit. The
existing release workflow promotes those exact three ZIPs to Draft; it must not rebuild
or substitute bytes. Publish only after their existing candidate consumer checks pass,
then deploy the immutable Release as above. Release publication, readiness, actual
Worker execution and browser acceptance remain separately recorded facts.
