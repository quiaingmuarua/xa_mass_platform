# Shared Product Preview

Status: current shared product launch and archive owner.

This finite preview starts one Server and one Scenario Host. SMS and Messages use
one Redis scope, one WebSocket Adapter and the same Worker pool. It adds no product
framework or platform owner. The production Runtime ZIP continues to exclude Host.

From the checkout root, install Python prerequisites and launch:

```powershell
python -m pip install -r distribution/product-preview/requirements.txt
python distribution/product-preview/run_preview.py --build
```

From this directory the entry is `python run_preview.py --build`. Build requires
Java 21, Node 22.19+ below 25, Corepack/pnpm 11.9.0 and Gradle's existing wrapper.
Run requires external Java 21, Python 3.11+ and Redis 7. It does not start or stop Redis.
`XA_MASS_REDIS_URL` defaults to `redis://127.0.0.1:6379/15`.

Default `--products sms,messages` serves Messages at `http://127.0.0.1:18500/messages`,
SMS at `/sms`, Runtime at `/runtime/workers`, and the Host at `http://127.0.0.1:18504/lab`.
Select `--products sms` or `--products messages` for one product. `--counts 4,4,4`
sets CN/US/GB pools, default 20 each; the fixed large proof uses `700,200,100`.
`--port` sets Server base, Adapter +3 and Host +4. Occupied ports fail without
stopping the existing service. Ctrl+C stops only owned processes and cleans the
exact generated `test_products_<UUID>` scope through SCAN/UNLINK.

`config/application-product-preview.yaml` owns Server/Adapter/Endpoint coordinates.
The independent product profiles enable business; they do not contribute another
Adapter or Redis owner. Every selection uses one mixed-country `demo-sim` Group
and Host Manager. This configuration enables its Matching country index: SMS
uses indexed ON_DEMAND, Messages uses a country constraint in PRECOMPUTED rules.
Only enabled products contribute handlers. Each catalog must initialize before Host starts.
This is the sole Preview launcher, deployment configuration and ZIP for all three
selections. Business modules retain their own APIs and acceptance oracles.
The shared HTTP client reuses a connection per thread and endpoint while requests
remain less than five seconds apart. It closes an idle connection before the next
request; a failed or uncertain mutation is surfaced without automatic replay.

```powershell
.\gradlew.bat :distribution:product-preview:previewZip
python distribution/product-preview/verify_archive.py --archive distribution/product-preview/build/distributions/xa-mass-product-preview-0.1.0-preview.zip --frontend frontend/dist
```

The ZIP contains the current Server JAR, Host classpath, unified frontend, config,
Python entry and requirements. After extraction install `requirements.txt` with pip
and run `python run_preview.py`; select SMS alone with `--products sms`. No Node or
Gradle is needed. The manifest identifies versions, HEAD, enabled defaults and
SHA-256 fingerprints of binaries, frontend, launcher and deployment config.
Source staging uses this module's `build/server`, `build/host` and `build/frontend`;
the frontend includes the current build's generated diagnostic dictionary. It does not reuse
a possibly running installation in another preview. All staging inputs come from
the same Gradle graph as the ZIP.

Run the external [proof runner](../../integrations/product-coexistence/README.md)
with `--root <extracted-directory>`; it loads the packaged launcher and starts the
packaged artifacts. Archive verification compares frontend bytes and all manifest
fingerprints. Runs store private process metadata/logs beneath `build/runs`; CI
publishes only safe summaries, never message bodies, replies or full Properties.
The [SMS acceptance runner](../../products/sms-reception/README.md#检查与验收)
retains its functional, lifecycle and fixed 1,000-Worker workload. It selects SMS
through this same launcher and also accepts `--root` to load an extracted launcher:

```powershell
python products/sms-reception/run_acceptance.py --build --scenario functional
python products/sms-reception/run_acceptance.py --scenario functional --root <extracted-directory>
```

Acceptance scripts and run evidence stay outside the ZIP. Launch lifecycle and
archive checks belong here; run them with
`python -m unittest discover -s distribution/product-preview -p 'test_*.py'`.
Both the SMS Preview workflow and Product Coexistence lane run these checks before
their separate business proofs.
