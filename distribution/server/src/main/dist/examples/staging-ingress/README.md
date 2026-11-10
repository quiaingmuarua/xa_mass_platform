# Trusted staging ingress

Status: opt-in delivery configuration for one long-lived trusted staging environment.

This gateway protects access to the deployment. Every administrator credential
holder is trusted to select any development user/role. It does not authenticate
individual Console users or isolate one Worker Host from another Worker identity.
The two password files and realms are independent: administrator credentials
cannot Prepare or connect Workers, and Worker credentials cannot administer Tasks.

## Deployment inputs

Copy `deployment.example.json` outside the immutable Runtime extraction and supply
absolute Linux certificate, key, password-file, ACME and writable state paths.
The certificate must cover both hostnames. The renderer rejects unknown fields,
unsafe paths, duplicate Groups and non-loopback upstream configuration. No secret
value is accepted in the deployment JSON. The example Group must be registered and
its intended Pool/query enabled separately by the host; this gateway provisions none.

The published Runtime contains this directory and its `staging_ingress/` renderer
package. From `examples/staging-ingress` inside the extraction:

```sh
python3 -m staging_ingress render --config /etc/xa-mass/ingress/deployment.json --output /etc/xa-mass/ingress/nginx.conf
nginx -t -c /etc/xa-mass/ingress/nginx.conf
```

From a source checkout use `python -m distribution.server.staging_ingress` instead.
Rendering writes only the requested configuration. It does not create credentials,
request a certificate, start Nginx, change DNS or open firewall ports. The generated
file is a complete standalone Nginx configuration, not a `conf.d` fragment.
Python is used only for one-shot preparation; it is not a Runtime Server or Nginx
process dependency. The file can be rendered on another machine and reviewed before
installation. The archive carries only the renderer, not its proof runner or test keys.
Create the state directory with `client_temp`, `proxy_temp`, `fastcgi_temp`,
`uwsgi_temp` and `scgi_temp` children (the latter three only satisfy standard Nginx
module initialization; no such upstream is enabled), writable
by the Nginx service. Keep password files/key readable only by the service/administrator.
The renderer intentionally does not choose an OS account. The deployment must run
Nginx workers as an explicit unprivileged service account; a privileged master may
use `-g 'user www-data;'`, with the state/password-file permissions assigned accordingly.
Alternatively run the whole service unprivileged with explicitly granted low-port
binding rights. Do not use root workers as the deployed configuration. The local proof
uses its invoking account and high loopback ports; it is not the production service unit.
Use distinct nonempty usernames per Host and independently generated passwords;
provision their htpasswd files outside the artifact. Never pass real passwords on
command lines or include them in proof summaries, payloads, Worker Properties or URLs.

The Runtime HTTP and Adapter listeners stay on `127.0.0.1`; Redis remains private.
Set the WebSocket public endpoint to
`wss://workers-staging.xamass.net/api/v1/worker-delivery/websocket`.
Adapter-to-Runtime HTTP continues to use its internal loopback address.
Only the externally running Worker Host needs its own Basic credential. Android
application provisioning is a separate Host concern; this example installs no Worker.

## Admission and limits

The administrator hostname admits only listed current API operations and Console,
documentation and static-asset GET/HEAD paths. Direct Call remains administrative.
Internal Adapter consume/append and Polling delivery, Worker Prepare and WebSocket
paths are never exposed on that hostname. The Worker hostname permits only POST
Prepare/Prepare batch for explicitly listed Groups and the exact WebSocket GET
Upgrade path. Everything else is denied, including unknown hosts and CORS preflight.
Encoded, semicolon, repeated-slash and dot-segment paths are refused. This bounded
staging surface supports ASCII resource IDs; it is not a general URL compatibility layer.

Administrator mutation requests reject foreign Origin and `Sec-Fetch-Site: cross-site`.
CLI calls without those browser headers still require Basic authentication. Port 80
serves only GET/HEAD ACME challenge files for the two known hosts; application HTTP
is denied without redirecting credentials. TLS is required. Certificate provisioning
must be completed separately before starting the final TLS configuration.

HTTP admission is limited per source address to admin 30 requests/s with burst 60,
and Worker 10 requests/s with burst 30. Bodies are limited to 16 MiB. WSS has a
100-connection limit per verified Basic username and 200 total, with a 1-hour idle
read/send timeout. The public WebSocket location authenticates first, then `try_files`
enters an internal named proxy location where `limit_conn` runs. Thus its key is
already verified; it does not rely on an empty/unverified pre-auth `$remote_user`.
The real Nginx proof exercises the actual 100/200 boundaries and checks that a wrong
password still receives 401 when that username is at its limit. HTTP request-rate
limits do not limit the subsequent WebSocket frame rate.

Basic and proxy credential headers are stripped before forwarding. Upstream retries
are disabled, including for mutation requests. Access logs contain the nonsecret
Basic username (credential key ID), method, normalized URI and status, never query
strings, Authorization or request/response bodies.
Application logs retain their existing owners and limits.

Changing an htpasswd file rejects new requests/upgrades. Existing WSS connections
remain active; a deliberate gateway stop/start closes them all. A graceful reload is
not claimed to revoke existing connections. This is a shared trusted environment,
not a credential-to-workerId authorization system.

## Proof

The deterministic tests run from source with:

```sh
python -m unittest discover -s distribution/server/src/test/python -p test_staging_ingress.py
```

The Linux proof requires `nginx`, `openssl` and Python 3.10+. It creates a private CA
and a localhost/IP SAN certificate, verifies TLS normally, and runs only its own
Nginx and loopback mock upstream processes. It requires neither Redis nor an external
DNS/server. From source:

```sh
python -m distribution.server.staging_ingress.proof --output /tmp/xa-mass-ingress-proof
```

Use a fresh output directory. The CLI retains only the safe `summary.json`; private
fixture keys/configuration/logs live in an owned temporary directory that is removed
after stopping its processes, including on failure. The proof checks routing,
credentials, request filtering, body/rate/connection limits, no retry, revocation and
restart. One unchanged raw WSS connection also stays silent for 65 seconds and then
echoes a frame; no heartbeat or SDK reconnect can mask the default 60-second proxy
timeout. This bounded witness does not measure the full configured one-hour idle limit.
It is not evidence of Worker execution. The Runtime Distribution caller may
use `staging_ingress.proof.Gateway` around real loopback Runtime/Adapter ports, import
its test CA into the actual SDK JVM truststore, and retain the separate finite-task
execution witness. Callers of that lower-level fixture own their private directory's
cleanup and must upload only safe evidence. Neither successful gateway tests nor a published Release proves
that a particular external cloud environment permits network access to this host.
