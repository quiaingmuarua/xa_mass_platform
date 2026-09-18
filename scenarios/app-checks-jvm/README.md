# 应用注册查询（app-checks）

Status: current one-shot application check scenario owner.

`app-checks` 是 Preview 的后端场景：同一国家的一批号码和一个模拟描述，创建
`CLOSE_WHEN_IDLE` Task，追加 Items 后自动批准。`app-a` 使用 `app-a-sim`，
`app-b` 使用 `app-b-sim`。国家只校验号码，不筛选 Worker 国家。
Console 的 `/app-checks` 页面直接使用以下 API；详情可通过
`/app-checks/tasks/{taskId}` 打开，Server 重启后继续读取原 Task。

**业务执行契约：已注册和未注册都属于执行成功；失败区间让真实 Handler 抛异常。**
Kernel、Matching、Pacer、SDK、Adapter 的调度和执行机制没有变化，没有新增 Task
模式、Redis key、Evidence 队列、回执或业务结果缓存。

## API 和 Task 数据

| API | 内容 |
| --- | --- |
| `GET /api/v1/app-checks/catalog` | Project、App/Group 映射、国家、容量和描述示例 |
| `POST /api/v1/app-checks/tasks` | 请求内创建、追加、自动批准；201 返回 `{"taskId":"..."}` |
| `GET /api/v1/app-checks/tasks?limit=100` | Project 倒序列表，最多 100，无分页 |
| `GET /api/v1/app-checks/tasks/{taskId}` | Task、数量观测、最多 100 条 Result 预览 |

创建示例：

```json
{
  "requestId": "check-cn-app-a-001",
  "name": "App A CN 查号",
  "appId": "app-a",
  "country": "CN",
  "numbers": ["+8613800000001", "+8613800000002"],
  "simulation": {
    "ranges": {
      "registered": [0, 500],
      "unregistered": [500, 900],
      "failed": [900, 1000]
    },
    "delayMs": [2000, 5000]
  }
}
```

`requestId` 必填，`name` 可选，均为非空字符串且最多 128 字符。每 Task 1..1000
个号码，去首尾空白后拒绝重复。号码为 `+` 后 2..15 位数字，首位非零，符合 CN
`+86`、US `+1` 或 GB `+44` 前缀，前缀后至少一位。只检查格式，不证明真实地区或可达性。

Server 生成 Task/Item ID。Task priority=50、maxRetryTimes=3；Item priority=5、
TTL=10 分钟。Task 声明 `any` Pool、空 target、水位 100；Item 使用 `worker.any({})`。
名称存在 descriptor；metadata 保存 `scenario、appId、country、simulation、salt、saltDate`。
号码和执行参数只在 Items，不保存号码列表副本或数量。

完整校验后才进入幂等和容量准入。最多两个新提交并发，进程内最多 50 个请求、
50,000 个号码。相同 requestId 和规范化内容共享一次提交，不同内容 409，容量不足
在副作用前 429。每次追加最多 100，全部确认后批准。写入不明返回 503 和已知 Task ID，
保留同一次提交结果；不重建、不重试、不补偿删除。关闭停止准入并最多等待 5 秒。
幂等不跨 Server 重启，持久 Task/Items/Results 查询不依赖幂等记录。

列表保留 managed Task 和无业务 metadata 的 Task，只给明确的 app-checks 有限 Task
解释业务配置和数量。详情严格校验 Project。`totalCount` 来自 Item Score 成员总数，
`activeCount` 来自 tag 1，`succeededCount` 为 tag 6..9，`failedCount` 为 tag 5。
已注册/未注册都计执行成功；不从预览推算全任务注册分布。

Result 预览复用一次 Owner HSCAN 和对应 Item 批量读取，最多 100 条且不补扫，
`resultsTruncated` 表示未完整展示。`resultStatus` 与可选 `registered` 分开；失败行
没有业务答案。无法解析或号码/Group 关联不符时保留原执行状态并返回 `contentError`。
结果不保证文件顺序或最新顺序；统计、Task 状态和 Result 不承诺共同快照。

## Simulator 协议与可复算性

事件固定为 `extension.worker.app.registration.check`。Item 参数只有
`number、salt、ranges、delayMs`，不接受请求指定 Worker 身份。
区间为整数左闭右开，位于 0..1000，完整覆盖且互斥，可为空。`delayMs` 恰好两个整数，
0 ≤ min ≤ max ≤ 30000，相等为固定延迟。simulation 最多 4096 字符，缺失或未知字段拒绝。
API 和 Worker 各自在自己的远程输入边界校验，不新增共享协议模块。

首次受理按 UTC 日期生成 salt：对 UTF-8 字段元组
`["app-checks/v1/salt", appId, country, normalizedNumberCount, "YYYY-MM-DD"]`
逐字段加四字节大端字节长度，SHA-256 完整摘要转小写十六进制。数量用十进制字符串。
salt 与日期保存后固定；请求重试、查询和执行重试不重新生成。相同 App、国家、数量和
日期得到相同 salt，名称、requestId、Task ID 不参与。

Handler 进入时捕获实际 workerId，Group 来自构造绑定。结果及延迟分别计算：

```text
tuple = ["app-checks/v1/" + domain, workerId, salt, number]
encoding = 对每字段追加四字节大端 UTF-8 字节长度 + UTF-8 字节
value = SHA-256(encoding) 的前八字节，按大端无符号整数解释
outcome bucket = value(domain="outcome") mod 1000
delay = min + value(domain="delay") mod (max - min + 1)
```

固定向量：worker-a / fixed-salt / +8613800000001 → bucket 25；延迟范围
[2000,5000] → 4067ms。worker-b 同输入 → bucket 917、4911ms。

先等待所算延迟，再返回成功或抛 `WorkerException(EVENT_EXECUTION_FAILED)`。
成功内容为 `number、registered、workerId、workerGroupId、simulatedDelayMillis`。
中断恢复线程标记并走既有失败路径。没有业务线程、状态缓存或 Reporter。

相同实际 Worker 和输入可以复算；重试换 Worker 后结果可以变化。当前执行 claim
为 5 秒，较长延迟可能重试并产生迟到结果。场景不续租，也不承诺只执行一次。
最终 FAILED 本身不能证明 Handler 执行过，真实失败证明另有测试装配内的调用见证。

## 页面与结果核对

页面沿用任务列表、创建抽屉和详情。列表最多 100 条；号码文件为 UTF-8、最大 1 MiB，
每行一个同国家号码、最多 1000 个。名称自动生成为
`check-{appId}-{country}-{count}-{本地提交时间}`；Task ID 与 salt 仍由 Server 生成。
支持四种范围示例，切换范围保留 delayMs；全部追加确认后自动批准。
不确定提交保留草稿和已知 Task ID，不自动重建或重试。

详情分别展示 Task 状态、Score 数量及最多 100 条 Result。未注册是执行成功；执行失败
没有注册答案，业务解析错误不改判执行状态。列表和详情仅进入及手动刷新时读取；不增加
分页、导出、自动观察或统计缓存。统计不能由预览行数推算。

“核对当前预览”使用存量 salt、实际 workerId、号码和原模拟描述，按上面的 SHA-256
协议在浏览器复算注册答案、Group 和模拟延迟。BigInt 保留无符号精度，模拟延迟不是
端到端耗时。失败或资料缺失记录标为无法核对；不增加 API，不写回平台，不证明执行次数。
新快照清除旧核对。Web Crypto 不可用时明确禁用，不进行远程代算。
显式 Mock 与 API 共用页面，Mock 样例及本地创建无网络请求，不作为 API 失败回退。

## 装配与证明

[Boot](../../server_boot_jvm/README.md) 仅在 preview 装配该场景及两个 Group，
普通 platform profile 不启用 API。每个 App Group 默认 20 个 Worker；
[Preview](../../distribution/server/PREVIEW.md) 的 `--app-count` 控制每组数量，
0 从本次 Host 配置排除两个 Group。`--count` 仍只控制 demo-sim。
已有库存按原规则复用，不修改 demo-sim，也不清理业务数据。

```powershell
.\gradlew.bat :scenarios:app-checks-jvm:test :worker_simulator_jvm:test :server_boot_jvm:test
.\gradlew.bat :server_boot_jvm:scenarioCompositionIntegrationTest
python -m unittest discover -s scenarios/app-checks-jvm -p 'test_*.py'
python scenarios/app-checks-jvm/run_acceptance.py --build --port 18620
python scenarios/app-checks-jvm/run_acceptance.py --root <freshly-extracted-preview> --port 18640
```

单元测试证明准入、固定 hash、半开边界、异常、中断、提交幂等及部分失败。Boot 的
真实 Redis/HTTP/Worker 测试用两个 Group 各两个 Worker，证明 101 条预览有界、真实
异常调用、未注册成功、Group 隔离、6 秒迟到成功及 Server 重启读取。调用见证仅在测试
装配中，溢出失败，不增加生产日志或队列。

进程 runner 复用现有 Preview 启动/清理，四个 App Worker 加一个闲置 demo Worker，
创建四个 Task 共 30 Items，最多每 Task 45 秒观察；独立 Python 复算成功结果和延迟。
不要求混合比例、均匀分配或固定执行者；进程 runner 不以终态失败证明执行次数。
CI 使用现有 `product_coexistence` lane 执行源码及新解压 ZIP；原 SMS/Messages
proof 显式 `app_count=0`，负载和断言保持原样。公开产物只包含阶段、数量和校验摘要，
原始日志与业务内容留在 private；每次使用唯一 test scope 并仅 SCAN/UNLINK 该 scope。
