# consumer with Batch Checks and Messages

The `consumer` profile enables Batch Checks and Messages by default. This example
supplies their external configuration without repeating either `enabled: true`.
It starts no Worker process and includes no SMS or window policy. SMS retains its
Preview-only assembly.

## Prepare the inputs

Copy the three input files to a deployment-owned directory outside the immutable
Runtime extraction:

| File | Purpose |
| --- | --- |
| `runtime.yaml` | User-directory location, business bindings and enabled Any/Messaging capabilities. |
| `worker-groups.json` | The existing Group initializer registers `example-workers` and its two events. |
| `batch-checks-configuration.json` | First-start seed for the MD5 check, grouping of one seed per Item and unit price 1. |

Replace every `/replace/with/absolute` path in the copied Runtime YAML. Prepare a
private users file using the Server user-directory contract; this example ships
no users or credentials. Configure the operator's Project grants for `app-checks`
and `messages`. Use an absolute filesystem path for the writable check document,
and absolute `file:` URIs for its seed and the users file. Windows paths and URIs
can be obtained with `Resolve-Path`, `[IO.Path]::GetFullPath` and `[uri]`.

The seed is read only when the writable check document does not exist. Reusing
the data directory preserves saved configuration; editing only the seed does not
change an existing deployment. The configuration page saves the actual document,
and a deliberate Server restart applies it. Keep configuration, lock files,
inventory and archives outside the extracted artifact.

Both business Projects are contributed by their modules. Do not repeat them in
host YAML. The example separately declares the optional raw `default` Project.
Group membership and Pool enablement remain independent; creating a check does
not enable its Pool or install its Handler.

## Source launch

After editing the copied files, use the existing local entrypoint:

```powershell
python run_local_runtime.py --profile consumer `
  --runtime-config 'D:\deploy\business-inputs\runtime.yaml' `
  --groups 'D:\deploy\business-inputs\worker-groups.json' `
  --work-dir 'D:\deploy\business-runtime' `
  --server-port 19182 --adapter-port 19183 `
  --redis-url redis://127.0.0.1:6379/15 --redis-scope consumer_business_example
```

No Java or Android Worker is selected by this command. Worker options remain
separate; the Server does not launch a Host. The scope is explicit and retained.
The launcher reads no inherited business environment overrides.

## Extracted Runtime ZIP

Use the same copied files. On Linux, with absolute installation/input/data paths:

```sh
runtime=/opt/xa-mass/runtime
inputs=/etc/xa-mass/business-inputs
data=/var/lib/xa-mass
jar=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["serverJar"])' "$runtime/manifest.json")
java -jar "$runtime/$jar" \
  --spring.profiles.active=consumer \
  --spring.config.additional-location="file:$inputs/runtime.yaml" \
  --xa.mass.worker-assembly.group-config-json="$(cat "$inputs/worker-groups.json")" \
  --spring.web.resources.static-locations="file:$runtime/frontend/dist/" \
  --xa.mass.task-retention.directory="$data/task-archives" \
  --xa.mass.redis.url=redis://127.0.0.1:6379/15 \
  --xa.mass.redis.scope=consumer_business_example \
  --server.port=19182 \
  --xa.mass.worker-delivery.adapter.instances.runtime-websocket.listen-port=19183 \
  --xa.mass.worker-endpoints.endpoints.runtime-websocket.public-uri=ws://127.0.0.1:19183/api/v1/worker-delivery/websocket
```

These are local connection coordinates. A remote deployment supplies its own
advertised Worker endpoint and ingress through the existing deployment procedure;
using this example does not deploy or update GCP. The ZIP's default profile stays
`default`, and files under `examples` are never automatically loaded.

## Connect Workers and use the businesses

A valid deployment can become Ready with zero online Workers. Catalog, task
creation and input import are available; execution needs external Workers with
the advertised Handlers and qualifying properties. Registration is not proof of
an installed Handler or available stock.

For this example, install the existing UTF-8 MD5 and five-field message-send
Handlers in `example-workers`. Messaging Workers publish `messaging.enabled`
as the string `true`, a supported `country` and the sender information required
by that Handler. Android's digest protocol differs from this example's string
MD5 protocol; its dedicated Batch Checks example retains its matching contract.

Use `/app-checks` for the MD5 prefix check and `/messages` for message tasks.
Fund the intended user explicitly before approving priced checks. Results,
Messages later receipts, per-seed CSV and raw terminal JSONL use the existing
business and Task APIs. Startup does not fund accounts or create business Tasks.

## Select a smaller composition

An older consumer deployment that relied on business defaults being off must
make its intended composition explicit before using this version:

```yaml
xa:
  mass:
    scenarios:
      app-checks:
        enabled: false
      messages:
        enabled: false
```

For Messages-only, disable only `app-checks`; for Batch Checks-only, disable only
`messages`. Disabled businesses need no missing configuration filled in. Enabled
businesses reject missing configuration or resource dependencies instead of
falling back to Preview data. This change adds no empty-business setup state,
SMS switch, new profile or compatibility alias.
