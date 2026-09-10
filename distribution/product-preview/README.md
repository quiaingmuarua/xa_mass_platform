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
Adapter or Redis owner. Distribution supplies neutral `demo-*` Groups for both,
`sms-*` for SMS alone and `messages-*` for Messages alone. Host scene selection
matches that composition. Both product catalogs must initialize before Host starts.

```powershell
.\gradlew.bat :distribution:product-preview:previewZip
python distribution/product-preview/verify_archive.py --archive distribution/product-preview/build/distributions/xa-mass-product-preview-0.1.0-preview.zip --frontend frontend/dist
```

The ZIP contains the current Server JAR, Host classpath, unified frontend, config,
Python entry and requirements. After extraction run `python run_preview.py`, with
no Node or Gradle. The manifest identifies versions, HEAD, enabled defaults and
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
The [SMS standalone entry](../../products/sms-reception/README.md#启动与交付) remains available.
