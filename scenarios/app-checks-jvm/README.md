# 应用注册查询（app-checks）

Status: current one-shot application check scenario owner.

`app-checks` 是 Preview 的 one-shot 查询场景。创建空的 `CLOSE_WHEN_IDLE`
Task、导入号码、核对启动和关闭是独立操作，复用 Server 的 Task 应用能力。
App/Group 映射由 [Boot](../../server_boot_jvm/README.md#platform-and-preview) 装配。
国家只校验号码格式和前缀，不筛选 Worker 国家或证明号码归属地。

已注册和未注册均属于执行成功；失败区间让真实 Handler 抛异常。Kernel、Pacer、
Matching、SDK 和 Worker 协议保持原样，没有新增业务 Redis key 或导入后台任务。

## API 和 Task 数据

| API | 内容 |
| --- | --- |
| `GET /api/v1/app-checks/catalog` | Project、App/Group、国家、单次导入限制及模拟示例 |
| `POST /api/v1/app-checks/tasks` | 创建空待审核任务，201 返回 `{"taskId":"..."}` |
| `POST /api/v1/app-checks/tasks/{taskId}/numbers:import` | `text/plain` UTF-8 号码文件；全文件校验后每批最多 100 个 Item |
| `POST /api/v1/app-checks/tasks/{taskId}/approve` | JSON 整数为用户确认的号码数量；与当前数量一致且非零才批准 |
| `POST /api/v1/app-checks/tasks/{taskId}/close` | 取消或中止有限任务，复用通用关闭能力 |
| `POST /api/v1/app-checks/tasks/{taskId}/results:export?filter=all` | 终态 CSV；filter 为 all、registered 或 unregistered |
| `GET /api/v1/app-checks/tasks?limit=100` | 最近一批 Project 任务，最多 100，无分页 |
| `GET /api/v1/app-checks/tasks/{taskId}` | 有限场景 Task、数量观测和最多 100 条 Result 预览 |

创建输入只包含以下配置，不接受号码数组或自定义名称：

```json
{
  "requestId": "check-cn-app-a-001",
  "appId": "app-a",
  "country": "CN",
  "simulation": {
    "ranges": {"registered": [0, 500], "unregistered": [500, 900], "failed": [900, 1000]},
    "delayMs": [2000, 5000]
  }
}
```

Server 以版本化命名空间、Project、requestId 的长度前缀 UTF-8 SHA-256 生成稳定
Task ID，并在现有不可变 descriptor 保存请求身份与输入指纹。同身份同配置读取原
Task；不同配置冲突。已有 descriptor、Project 目录和 Task Score 不完整时返回未确认，
不另建或补造资源。关联在原 Task 资源保留期间跨 Server 重启成立，清理 scope 后不承诺。
没有原先累计 50 请求、50,000 号码的内存账本。自动名称、salt 和日期首次创建后固定。

导入仅允许 `inputVersion=2` 的待审核 Task。每个文件最多 10 MiB、100,000 个去重号码，
前端将 TXT、CSV 或粘贴统一转换为逐行 UTF-8 文件；后端独立校验 UTF-8、号码和国家前缀，
允许省略 `+`，去首尾空白、忽略空行并去重。无效文件整批拒绝，返回错误数量和首个错误行，
不会写入有效子集。请求局部原始与规范化临时文件在结束或失败后清理，不构成导入历史。

号码的稳定 Item ID 为 `number-` 加长度前缀元组 `["app-checks/v2/number", normalizedNumber]`
的 SHA-256，作用域为 Task。批次复用固定事件、任务盐和 Matching 查询，TTL 留空以采用
Runtime 默认有效期（当前 365 天），不从批准时重新计时。已存在且内容一致的 Item 跳过，
保留原时间、有效期、Score 和结果；不同内容拒绝覆盖。显式重传可用已存 Item 原值调用
既有 append 补齐缺失的初始 Score；Score 存在而执行数据缺失等其他不一致按数据异常处理。

Task priority=50、maxRetryTimes=3；Item priority=5。供给仍为 `any / {} / 100`，
新 Item 仍使用 `worker.assignment.available({})`。这些数字各自属于原 Owner 的边界。
旧任务及其 salt、随机 Item ID 不迁移；仍可读取、关闭和导出，不能追加 v2 号码。

同一 Server 实例通过既有 OperationGuard 串行化一个 Task 的导入、普通追加、批准和关闭；
一个同步导入的全部批次共享持有期。最多两个文件同时导入，结束释放容量，满时写入前背压。
不承诺跨实例串行。写入中断保留已确认批次，503 附带 taskId、confirmedAddedCount 和
existingCount；当前未确认批次不算拒绝，不自动重传、回滚或启动。用户可以显式重传、
补充其他文件、取消，或核对 Owner 当前实际数量后启动部分输入。审批不要求原文件事务完成。
关闭不会把未完成项计作成功，也不会阻止已有执行产生迟到结果。场景服务停机停止新准入并最多等待
正在导入的请求 5 秒，每个新批次检查停止信号。

导入成功回执给出 inputCount、emptyCount、duplicateCount、uniqueCount、addedCount 和
existingCount，均针对收到的号码文件。前端原始 CSV 的行数及去重数属于客户端校验摘要。
来源和回执仅在控制台会话保存；API 不根据 Task 数量重建来源或操作历史。

列表仍包含 Project 目录中的 managed/无业务 metadata 行，由前端过滤；详情和写入验证
Project、场景和有限 Task 类型。总量来自 Item Score 成员数，active 为 tag 1，failed 为
 tag 5，succeeded 为 tags 6..9。Result 预览只读一次 Owner HSCAN，最多 100 条，不补扫，
不保证文件顺序或最新顺序。失败行没有注册答案；内容或 Item/Group 关联异常保留执行状态并
标记 contentError。统计、Task 状态和 Result 不是共同快照。

CSV 复用通用终态成功结果导出，再按每批最多 100 个 Item 做业务关联校验及筛选，包含号码、
注册状态、应用、地区，保护文本单元格并正确转义。失败、异常内容和缺少注册答案的记录不导出。
文件生成完成后才通过 `X-Export-Count` 返回准确行数，不额外扫描以预估数量。原始 JSONL 与
CSV 临时文件都在失败或传输结束时清理。导出独立于预览筛选；迟到结果可使下一次导出内容变化。

## Simulator 协议与可复算性

事件固定为 `extension.worker.app.registration.check`。Item 参数只有
`number、salt、ranges、delayMs`，不接受请求指定 Worker 身份。
区间为整数左闭右开，位于 0..1000，完整覆盖且互斥，可为空。`delayMs` 恰好两个整数，
0 ≤ min ≤ max ≤ 30000，相等为固定延迟。simulation 最多 4096 字符，缺失或未知字段拒绝。
API 和 Worker 各自在自己的远程输入边界校验，不新增共享协议模块。

v2 创建首次受理按 UTC 日期生成 salt：对 UTF-8 元组
`["app-checks/v2/salt", appId, country, requestId, "YYYY-MM-DD"]` 逐字段加四字节
大端长度，计算 SHA-256 小写十六进制。号码数量不再参与。salt/date 在 descriptor 固定，
重复创建返回原任务，不用请求重试时的新日期覆盖。v1 存量任务仍读取原 salt，Worker 的
outcome/delay 算法和固定向量不变。

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

## 分配窗口投影

Boot 为每个 App Group 装配本场景的纯属性投影，只接收
`observationEventName=worker.assigned` 且
`messageEventName=extension.worker.app.registration.check` 的通知。
Pacer 在执行租约和 Item claim 均成功后、Command 编码和发布前发出通知。
同组其他事件、选择候选但分配失败、仅取得租约都不计入。

投影只写两个 Platform Properties：`lastAssignedAt` 和 `windowAssignmentCount`。
前者是最近观察到的分配时间（毫秒），后者是该时间所在固定窗口中的观察数量。
窗口长度读取各 Group 的 `xa.mass.worker-matching.groups.<group>.assignment-window.window-millis`，
与 Matching 使用同一份启动配置；窗口编号为 `floor(observedAtMillis / windowMillis)`。
同窗口累加、时间取最大值；新窗口重新计数；
更旧窗口不回退。两个字段均不存在时初始化；字段不完整、非整数、负数或溢出时
整个 Worker 投影跳过并由 Server 计入处理失败诊断，不静默修复。其他属性不修改。

这里统计的是 best-effort **分配观察**，不是 Handler 实际执行次数或成功次数。
后续发送、执行失败不会扣减；重试再次分配会再次通知。Server 队列丢弃、进程退出、
Facts 缺失或属性写入失败均可能造成缺口，没有补发、最终一致性或精确额度保证。
不定时清零，空闲时保留最近窗口；重启读取已写入值，不补齐未处理通知。
新建 Item 的 Matching 函数读取这两个字段，投影只提供选择和纯计算，由 Server 的同一个
Properties Handler 合并读取与写入；场景不依赖 Pacer 或通用 FunctionHandler。
固定路由、异步交接及既有 patch/候选失效的完整边界见
[Server](../../server_jvm/README.md#worker-allocation-observations)。

## 分配窗口筛选

新 Item 使用 `worker.assignment.available({})`；窗口和阈值由
[Boot 的 Group 配置](../../server_boot_jvm/README.md#platform-and-preview) 决定。
该函数用现有 Any Pool 候选和 Platform Properties 判断资格；完整字段解释、失败与预算见
[Matching Owner](../../worker_matching_jvm/README.md#observed-assignment-window)。

跨窗口意味着重新符合条件，不承诺立即执行。观察异步、可丢失，不能把配置阈值当成严格
执行上限；失败和迟到结果不会扣减统计。范围是 Group 内的单 Worker，没有跨设备
IP／账号配额。其他显式函数不受这一策略拦截。窗口长度改变使用新 scope，不迁移旧值。

## 页面与结果核对

启动 [Preview](../../distribution/server/PREVIEW.md#source-launch)，打开 `/app-checks`，
选择 App 和号码国家，前端依次创建空任务、上传号码文件，用户核对实际数量后启动。
列表菜单管理任务，抽屉预览结果；未确认创建或导入保留请求身份及已知 Task ID，不自动重试。

“核对当前预览”按上面的 SHA-256 协议，用存量 salt、实际 Worker、号码和模拟描述复算；
它只核对已展示内容，不证明执行次数或失败原因，模拟延迟也不是端到端耗时。
[前端 Owner](../../frontend/README.md#app-checks-task-workspace) 维护文件导入、草稿、刷新、
API/Mock 和浏览器核对的可用性及本地状态。

## 装配与证明

生产装配见 [Boot](../../server_boot_jvm/README.md#platform-and-preview)；
Host 数量参数和库存复用见 [Preview](../../distribution/server/PREVIEW.md#inventory-and-process-lifecycle)。
以下 `--build` 验证源码 staging，`--root` 验证新解压 ZIP；后者不回退源码启动器或在包内构建。

```powershell
.\gradlew.bat :scenarios:app-checks-jvm:test :worker_simulator_jvm:test :server_boot_jvm:test
.\gradlew.bat :server_boot_jvm:scenarioCompositionIntegrationTest
python -m unittest discover -s scenarios/app-checks-jvm -p 'test_*.py'
python scenarios/app-checks-jvm/run_acceptance.py --build --port 18620
python scenarios/app-checks-jvm/run_acceptance.py --root <freshly-extracted-preview> --port 18640
```

单元测试证明轻量创建、稳定身份、十万号码有界导入、全文件校验、部分失败、互斥及重复输入处理。
真实 Redis/HTTP 另外验证十万号码写入、跨文件去重、原时间保持、默认有效期、重启幂等、审核数量检查和关闭。Boot 的
真实 Redis/HTTP/Worker 测试用两个 Group 各两个 Worker，证明 101 条预览有界、真实
异常调用、未注册成功、Group 隔离、6 秒迟到成功及 Server 重启读取。调用见证仅在测试
装配中，溢出失败，不增加生产尝试日志或场景队列。

额外的分配观察证明保持实际 Redis、Server、Adapter 和 Java Worker：在测试装配中
阻塞 Handler 返回 Report，先确认 Platform 窗口已推进，再放行成功、异常及 6 秒
迟到结果，并验证重启保留属性。测试有界记录真正的 Pacer 通知，用其源时间和
Worker 关联复算窗口，不用 Item 数量或 Result 数量推算。该见证不进入生产代码。

窗口专用 Boot 用例使用一个实际 Worker、60 秒窗口、阈值 1：真实分配通知推进属性后，
通过实际 Facts 快照读取和空 take 结果见证筛选拒绝；跨窗后等待正常 60 秒候选回收、
补货与执行，不手动清零、恢复库存或改 Score。恢复观察最多 180 秒，字段边界另由
受控时钟单元测试证明。投影延迟允许继续选择，测试不把阈值当成严格配额。

原 101 Item 等非窗口 Boot 回归显式把阈值设为 1000，仍走新函数。
进程 runner 通过标准 `SPRING_APPLICATION_JSON` 仅覆盖两个 Group 的阈值为 1000，
保留并恢复调用环境，在摘要记录覆盖值。它验证原业务闭环，不声称验证默认窗口限额。
Preview 的默认 60 秒／10 次保持。进程 runner 复用现有 Preview 启动/清理，四个 App Worker 加一个闲置 demo Worker，
先只读确认已知 App Worker 的 Properties 已上报，再创建四个 Task 共 30 Items，
最多每 Task 45 秒观察；独立 Python 复算成功结果和延迟。
不要求混合比例、均匀分配或固定执行者；进程 runner 不以终态失败证明执行次数。
CI 使用现有 `product_coexistence` lane 执行源码及新解压 ZIP；原 SMS/Messages
proof 显式 `app_count=0`，负载和断言保持原样。公开产物只包含阶段、数量和校验摘要，
原始日志与业务内容留在 private；每次使用唯一 test scope 并仅 SCAN/UNLINK 该 scope。
