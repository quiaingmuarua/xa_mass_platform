# XA Mass 4.0 release and upgrade contract

Status: version-scoped 4.0 release contract; publication requires complete manual Proof at the
exact candidate commit. Published 3.x artifacts remain immutable.

The changes below apply to the 4.0 series. GCP's purpose and deployment cadence belong to the
[Release Preview guide](../../doc/guide/operations/deploy.md#release-preview). This release's
upgrade path is not a commitment to preserve interfaces, configuration or data across future
releases.

## Breaking change: access-key API identity

Every non-exempt `/api/**` request must carry exactly one `Authorization: Bearer <access key>`.
The development headers `X-XA-Dev-User` and `X-XA-Dev-Role` are removed without an alias:
requests that send only them receive `401/18008`. Error code `12012` is removed; an invalid
requested owner filter now returns `400/12001`. The exempt routes are the Worker Prepare,
Worker polling and internal Adapter routes listed in the
[Server front door](../../server_jvm/README.md#access-key-front-door). Kernel, Pacer, Matching,
Adapter, Worker SDK and Android Worker wire contracts are unchanged; 3.2 SDK clients keep
connecting through the Worker gateway credential.

A key belongs to one directory user; role and Project grants come from that user's current
record. `GET /api/v1/me` returns the caller's record. The users file now persists Console user
edits, and administrators issue or clear another user's key through
`POST|DELETE /api/v1/users/{userId}/access-key`. There are no passwords, sessions,
registration, key scopes, expiry or usage statistics, and no Kernel user domain.

## Configuration changes

- `consumer` requires `xa.mass.users.seed` (or `XA_MASS_USERS_SEED`) and fails startup
  without it. `default` and `preview` keep the built-in development directory, whose users
  carry the public keys `xak_dev_<userId>`; never expose that directory on an external host.
- The users file gains the optional per-user `accessKeySha256`. A `file:` seed must be in a
  directory writable by the Runtime service account, because the Server persists writes and
  holds a sibling `.lock` file. Hand edits belong to a stopped Server.
- The staging ingress deployment input drops `admin_password_file`. Its administrator host has
  no Basic realm and forwards only well-formed Bearer access keys; the Worker realm, TLS,
  allowlists, Origin checks and limits are unchanged.

## State and upgrade

Redis identities and Task data formats are unchanged, so a 3.2 deployment may keep its scope
and archive directory. Before upgrading, retain the 3.2 installation, external configuration,
users file and a usable Redis/archive restore point. Keep one Runtime/Pacer per scope.

1. Copy the existing users file to a Runtime-writable path, for example
   `/var/lib/xa-mass/users.json`, and point `xa.mass.users.seed` at it.
2. While the Server is stopped, write the first administrator's key hash with the
   [bootstrap recipe](../../doc/guide/operations/deploy.md#公网接入) and hand the key over privately.
3. Re-render the gateway from the 4.0 `examples/staging-ingress` renderer without
   `admin_password_file`, validate it with `nginx -t`, and remove the old administrator htpasswd.
4. Install the 4.0 Runtime separately, switch the exact JAR, start it and check local readiness.
5. Issue keys for the remaining users from the Console, then perform bounded acceptance.

On failure close public ingress before restoring the 3.2 binary, gateway configuration and
users file. Binary rollback does not undo completed tasks or key issuance in the 4.0 users file.

Performance A/B comparisons need a 4.0 or later baseline: the integration harnesses now send
Bearer keys, which 3.x Runtimes do not accept.

## Publication evidence

Use the existing complete manual Proof. Runtime Distribution now also proves, against the
packaged consumer profile with a temporary users file, that a created user's issued key
authenticates, survives a Runtime restart and is rejected after it is cleared. Its staging
ingress witness runs the real Runtime behind real Nginx with a private administrator key and
checks that the public development key and Worker Basic credentials are rejected for
administration. Promote the exact candidate Runtime/SDK ZIPs and their SHA256 sidecars and verify
immutable publication. GCP downloads the published Runtime and runs only a small deployment
acceptance, including the pending Console browser flow with a temporary acceptance user.
