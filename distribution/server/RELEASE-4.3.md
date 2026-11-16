# XA Mass 4.3 release and upgrade contract

Status: version-scoped 4.3 contract. Publication requires the complete manual Proof at
the exact candidate commit. The current patch target is 4.3.1. Published 4.2 and 4.3.0
artifacts remain immutable.

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

The existing GCP deployment is a pure-platform consumer. Preserve that composition by
adding these explicit values to its external Runtime YAML before switching binaries:

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
`examples/consumer-business` files.

## State and GCP upgrade

Kernel Task/Item/Result data, Worker identities, users, credit records and archives keep
their existing formats. Reuse the current scope, users file, archive directory and
daily credentials; no identity migration, reset or Redis cleanup is required. The new
Server-owned `server:worker-connection-control:<groupId>` HASH under the existing scope
is absent until an administrator writes a decision. Missing records allow admission;
unreadable records or a failed store read do not.

Use the [Release Preview operating procedure](../../doc/guide/operations/deploy.md#release-preview):

1. Verify the published Runtime ZIP against its checksum, GitHub asset digest and
   manifest commit. Install it in its own immutable version directory. Record the
   operation start and take the single upgrade restore point covering configuration,
   users, ingress, Redis and archives, preserving ownership and permissions.
2. Stop any old external Host that would otherwise reconnect with an incompatible
   SDK. Prepare the matching 4.3 kit on the external acceptance machine; no Worker Host
   or build runs on GCP.
3. Apply the explicit Scenario disables above. Re-render Nginx from the existing
   deployment input using the new Runtime's renderer, which adds the administrator
   `GET/PUT .../connection-control` routes. Preserve the existing TLS, Worker allowlist,
   credentials and capacity settings. Validate with the documented Nginx service user
   before loading the result; Worker ingress must still reject management routes.
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
and previous binary. Do not clear Redis or replay uncertain tasks. The old Runtime does
not enforce new cooldown records, so binary rollback alone cannot preserve a newly
applied cooldown policy. Resolve that during the bounded upgrade window before
reopening ingress; never report the old Runtime as enforcing the new control.

## Acceptance focus

Complete manual Proof supplies the existing Owner, Redis, Runtime, Android, distribution
and business regression evidence at the release commit. GCP only checks the deployment
with a small workload:

- Public Console loads; unauthenticated API requests fail; a valid access key can read
  the deployment. The pure-platform consumer stays free of implicit business services.
- Start two external Java Workers from the published 4.3 kit. Run its shipped finite
  round through Any Pool, independently checking Item identity/state, MD5 Results,
  terminal Task and complete export.
- In Runtime Workers, use the administrator connection-control UI on one acceptance
  Worker. Set a future cooldown, observe its disconnect and refusal on explicit start,
  then release it and explicitly start it again. Confirm the second Worker stayed
  usable. After recovery, run another small finite round. Retain screenshot/API/Host
  evidence for the UI and actual admission outcome; an API-only run does not prove the
  browser operation.
- Preserve historical Android, browser and capacity records with their original
  versions and scope. Mark any unexecuted current-version flow as such. An older APK
  is not expected to reconnect until upgraded to the new SDK.

Use the standing acceptance user and a temporary `acceptance-4.3.1` Worker key following
the host's existing private-file procedure. Read current control revisions before
changes and retain a request identity on an unconfirmed response; never automatically
retry a mutation with a new identity. Finish with the test Worker's cooldown released.

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
