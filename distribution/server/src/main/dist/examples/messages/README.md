# Messages with the Android local channel

These are opt-in deployment examples, not automatically loaded Spring configuration.
`messages-runtime.yaml` and `worker-groups.json` are packaged from the XA Android
consumer's configuration sources. The first enables Messages and the Messaging
Pool/query for `android-demo-workers`; the second registers its declared events.
Messages contributes its own Project. Registration does not install a Handler,
connect a Worker or prove that stock is available.

From any working directory, use absolute paths. For example, in PowerShell:

```powershell
$runtime = (Resolve-Path '<extracted-runtime-directory>').Path
$yaml = ([System.Uri](Join-Path $runtime 'examples/messages/messages-runtime.yaml')).AbsoluteUri
$groups = Get-Content (Join-Path $runtime 'examples/messages/worker-groups.json') -Raw
java -jar (Join-Path $runtime 'lib/xa-mass-server-jvm-<version>.jar') `
  --spring.profiles.active=consumer `
  "--spring.config.additional-location=$yaml" `
  "--xa.mass.worker-assembly.group-config-json=$groups" `
  "--spring.web.resources.static-locations=$(([System.Uri](Join-Path $runtime 'frontend/dist/')).AbsoluteUri)" `
  --server.port=19182 `
  --xa.mass.worker-delivery.adapter.instances.runtime-websocket.listen-port=19183 `
  --xa.mass.worker-endpoints.endpoints.runtime-websocket.public-uri=ws://127.0.0.1:19183/api/v1/worker-delivery/websocket `
  --xa.mass.redis.scope=profile_android_messages `
  "--xa.mass.task-retention.directory=<absolute-data-directory>/task-archives"
```

The source consumer accepts the same two files with
`run_local_runtime.py --profile consumer --runtime-config <absolute-yaml> --groups <absolute-json> --server-port 19182 --adapter-port 19183 --android-count 1`.
It builds and starts the selected Android Lab App against the same coordinates,
then restores touched ADB mappings on exit. It reads no inherited business environment settings.

Build the Android Demo from the **same verified Runtime/SDK ZIP candidate**. No
Simulator server is used. The App publishes `messaging.enabled="true"`, `country=CN`
and a simulated sender phone, and installs `extension.worker.message.send`.
Open `/messages`, select **Android 本地消息演示**, import recipients, and explicitly
approve the finite Task. `SENT` means the App's local demo channel accepted the
message; it is not real SMS delivery.

In the Android Demo, **本地消息演示** opens the recent inbox. Opening a message
reports READ; the in-app reply and notification quick reply report REPLIED through
the original Worker run. Notifications require user permission and only the most
recent message gets a notification. Notification publication does not report DELIVERED.
Tasks finish without waiting for interaction. Original-run receipts remain
best-effort and cannot cross stop/start or process replacement.

Acceptance is process-local and bounded to 1,000 messages / ten minutes; optional
Reporter associations are limited to 256, and the inbox shows at most 100.
Reporter exhaustion does not prevent confirmed sending. A process restart loses
local acceptance/associations; retained Server results follow normal finite
retention. No cross-device or durable exactly-once claim is made.

The ordinary Messages page supplies bounded business previews. Complete terminal
success JSONL is available through the existing Runtime viewer / Task export API;
there is no separate Messages CSV format.
