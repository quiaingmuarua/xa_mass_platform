# Batch Checks external deployment

This is an explicitly loaded Android digest example, not a production default.
The `consumer` profile itself has no business Project, Group or enabled Scenario.
No Simulator is started. The Android App must install and expose the existing
digest Handler; Group registration alone does not prove that it can execute.

## Three inputs

| File | Owner and purpose |
| --- | --- |
| `batch-checks-runtime.yaml` | Host: enables Batch Checks and the Group's Any Pool/query; explicitly declares the optional raw `default` Project. |
| `worker-groups.json` | Host: Group attributes and event declarations, registered through the existing initializer. |
| `batch-checks-configuration.json` | Scenario seed: the check, saved execution settings, grouping and price. |

Batch Checks derives its own `app-checks` Project Groups from its effective checks.
Do not declare that Project again in host YAML. A check references resources; it
does not enable them. Pool Group membership remains independent of Project scope.

Copy these three files to a deployment-owned directory before editing. Keep the
Runtime ZIP extraction immutable. The writable Scenario document and finite Task
archives belong in a separate data directory that survives restarts. The seed is
used only when the writable document is absent; editing the seed does not update
an existing deployment. Use the configuration page, or edit the actual document
while Server is stopped. Changes take effect on restart; existing Tasks retain
their first execution snapshot. Window-length changes still require a new Redis
scope. Do not put runtime state or lock files back in this examples directory.

## Extracted Runtime ZIP: PowerShell

Use absolute paths for the installation, configuration and data. Replace the
example locations and Redis scope deliberately. Java 21 and Redis are required.

```powershell
$runtimeRoot = (Resolve-Path 'D:\deploy\xa-mass-server-runtime-3.0.0').Path
$inputs = (Resolve-Path 'D:\deploy\checks-inputs').Path
$dataRoot = [IO.Path]::GetFullPath('D:\deploy\checks-data')
$manifest = Get-Content -LiteralPath (Join-Path $runtimeRoot 'manifest.json') -Raw | ConvertFrom-Json
$env:XA_MASS_APP_CHECKS_CONFIG_PATH = Join-Path $dataRoot 'app-checks/configuration.json'
$env:XA_MASS_APP_CHECKS_SEED = ([uri](Join-Path $inputs 'batch-checks-configuration.json')).AbsoluteUri
$env:XA_MASS_GROUPS_JSON = Get-Content -LiteralPath (Join-Path $inputs 'worker-groups.json') -Raw -Encoding utf8
$env:XA_MASS_ADAPTER_PORT = '19183'
$env:XA_MASS_REDIS_SCOPE = 'checks_deployment'
$env:XA_MASS_REDIS_URL = 'redis://127.0.0.1:6379/15'
java -jar (Join-Path $runtimeRoot $manifest.serverJar) `
  --spring.profiles.active=consumer `
  "--spring.config.additional-location=$(([uri](Join-Path $inputs 'batch-checks-runtime.yaml')).AbsoluteUri)" `
  "--spring.web.resources.static-locations=$(([uri](Join-Path $runtimeRoot 'frontend/dist/')).AbsoluteUri)" `
  '--xa.mass.worker-assembly.group-config-json=${XA_MASS_GROUPS_JSON}' `
  "--xa.mass.task-retention.directory=$(Join-Path $dataRoot 'task-archives')" `
  --server.port=19182
```

The literal Spring placeholder passes Group JSON without shell quote conversion.
The packaged default profile stays `default`; placing examples in the ZIP does
not activate them. Read the resulting Catalog and Runtime resource bindings to
verify the effective deployment before admitting business work.

## Source checkout

Use the same three inputs and data environment values. With this repository as
`$checkout`, launch from any working directory:

```powershell
$env:XA_MASS_TASK_ARCHIVE_DIRECTORY = Join-Path $dataRoot 'task-archives'
python (Join-Path $checkout 'run_local_runtime.py') --profile consumer `
  --runtime-config (Join-Path $inputs 'batch-checks-runtime.yaml') `
  --groups (Join-Path $inputs 'worker-groups.json') --server-port 19182
```

The launcher builds only frontend and Server. Its HTTP port precedence is
`--server-port`, then `SERVER_PORT`, then `18182`; it explicitly passes the same
port to Server and readiness. Adapter coordinates remain in host configuration.
It never adds Projects, enables Pools, tops up credit or submits Tasks.

## Worker and business acceptance

Configure the real Android consumer against this Server/Adapter using the same
verified candidate Runtime/SDK pair. For its dual-ZIP launcher, explicitly supply
both `--runtime-config` and `--groups`; the file owns the host Project list.
An already built App must target these API/Adapter coordinates. An emulator may
use explicit ADB reverse mappings; retain and restore pre-existing mappings.

In the console, select the Android MD5 check, import text, review the actual seed
quantity and approve after an administrator explicitly funds the caller. The
sample price is 1 point per persisted seed. Compare raw Item/Result evidence and
CSV against independent MD5 vectors; do not infer execution from a declared event
or enabled Pool. Saved configuration and Task data survive the documented restart.

To add another Group using the same protocol, provide its registration JSON,
enable its Pool/query in host YAML, and add a check in the actual Scenario document.
Restart and validate dependencies. No profile edit or rebuilt artifact is needed.
Different input/result protocols require an existing or new pure Scenario contract.

The product name is Batch Checks / 批量检查. Current technical names (`app-checks`,
its Project and HTTP/page prefixes) intentionally remain unchanged.
