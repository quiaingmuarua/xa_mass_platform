# Messages Task Management

Status: current Messages business owner.

Messages 承载消息发送及后续送达、已读和连续回复的 tracked 业务过程。
当前发送通过模块声明的 `messages` Project 下有限 Task 提交，Task 结束后仍可接收后续响应；
共享 Group 和环境绑定由 [Boot](../../server_boot_jvm/README.md#platform-and-preview) 提供。

## 归属与流程变化

**本片将创建改为请求内完成，读取改为按请求观测。** 原 Campaign 提交队列、全量结果
观察循环、业务结果缓存和 metrics API 已移除。Kernel 调度、Matching、lease、claim、
TRACKED 与 Worker/Lab 协议没有改变。

| 信息 | 唯一来源 |
| --- | --- |
| 名称、业务配置 | Task descriptor 的 name/metadata |
| 创建时间、任务列表 | Project Task ZSET |
| 调度状态 | Task Score |
| 数量 | Item Score 区间观测 |
| 号码、执行参数 | TaskItem |
| 执行结果、回执、最新回复 | Result |

场景通过 Server 的 ProjectDirectory、ProjectTaskQueryService、TaskCreationService、
TaskDataService 和 TaskLifecycleService 组合读取与写入，不直接访问 Redis 或调度 Owner。
实现见 [MessageTaskService](src/main/java/com/xa/mass/scenario/messages/MessageTaskService.java)。

```text
完整校验 → requestId 幂等/容量准入 → Server 生成 Task ID
  → 创建 CLOSE Task → 每批至多 100 Items → 全部确认 → 自动批准 → HTTP 201
Worker message.send → HTTP 调用 Lab → SENT 执行结果
Lab 接收生成 delivered / 后续 read、reply → HTTP 回调原 Worker Reporter
  → 既有 Item Score / Result Owner
列表或详情请求 → 读取 Task / Score / Item / Result
```

## Worker 供给与装配

`MessageWorkerSupply` 集中 Project、发送事件、Pool、查询名称和 Worker 资格字段。
同一个装配入口从严格配置 `xa.mass.scenarios.messages.worker-group-id` 取得 Group，
注册 `ProjectDefinition`、`ProjectWorkerRequirements` 和资格定义，并将同一 Group
传给业务服务。Group 无默认值，不回退到 `scenarioWorkerGroup` String Bean；未知字段拒绝。
Project 身份固定为 `messages`，由模块声明，不在宿主 Project 列表重复配置。

`MessageCampaignsScenarioConfiguration` 注册纯数据 `QualifiedCountryDefinition`：
Pool=`messaging`、库存查询=`worker.messaging.available`、定向查询=`worker.messaging.phone`，
要求 Worker Properties 的 `messaging.enabled` 精确等于字符串 `true`，国家取 `country`。
这里没有新增 Platform Properties 或属性投影。

Server 收集声明，Matching 构造参数化资格、库存和定向查询；场景仅依赖该公开声明类型，
不持有 Matching 的库存、索引、策略或生命周期。声明 Bean 不依赖 Task 服务或已启动实例。
Group 仍由现有 Boot 配置分别启用 Pool／函数；指定号码查询可独立于 Pool 启用。
普通发送在补给时读 Worker Facts 并按国家入池，消费不再读 Facts；定向发送独立查 Phone
Index 后核对当前 Facts，仍共享同一物理 `phone` 索引。基础失败、预算与执行准入边界见
[Matching Owner](../../worker_matching_jvm/README.md#qualified-country-declarations)。

此次装配迁移保留所有 HTTP、供给／查询名称和输入、Task／Item／Result 与 Properties 格式，
存量任务无需重建。Project／Group 初始化、tracked Reporter 及提交流程沿用既有 Owner 路径。
平台测试显式提供自己的声明 fixture，不依赖本模块，也不在缺失声明时恢复内置默认规则。

当前业务 API 同时提供普通发送和指定号码发送，因此模块要求 send 事件、`messaging` Pool
以及上述两个函数。Server 在 Group 初始化后、Project Task 初始化前检查依赖；允许共享
Group 具有其他事件和资源，也允许使用外部已注册的 Group。需求只校验，不补注册事件、
启用 Matching 资源或覆盖 Group 属性。校验通过不证明真实 Worker 已安装事件 Handler。

## API 与业务输入

| API | 契约 |
| --- | --- |
| `GET /api/v1/messages/catalog` | Project、runId、版本、国家及受理上限，只有可用性含义 |
| `POST /api/v1/messages/tasks` | 同步完成提交与自动批准，201 返回 `{taskId}` |
| `GET /api/v1/messages/tasks?limit=100` | 创建时间倒序，1..100 个 Task，带 truncated，不分页 |
| `GET /api/v1/messages/tasks/{taskId}` | 项目内 Task、数量及最多 100 条 Result 预览 |

```json
{"requestId":"cross-country","name":"msg-US-CN-2-example","recipientCountry":"CN","senderCountry":"US","body":"{}","recipientIds":["+8613800000001","+8613800000002"]}
```

每次 1..1000 个号码，去首尾空白、禁止重复；国际号码为 + 加 2..15 位数字，首位非零，
CN/US/GB 分别要求 +86/+1/+44 且前缀后有号码。此检查不证明号码存在或真实国家归属。
requestId/name/senderPhone 最长 128 字符，body 最长 4096，必须为 JSON 对象字符串。
Lab 独占具体指令语义；Server 不重复解释步骤、概率和随机延迟。

**收件国家与发送国家独立。** senderCountry 省略/null 为 ANY；可选 senderPhone 与国家取交集。
未指定 senderPhone 时，Task 声明 messaging Pool 的 ANY／worker.country 供给，
Item 使用 worker.messaging.available 的 ANY／country 查询。
指定 senderPhone 时，Task 保存空供给声明，Item 使用 worker.messaging.phone(phone/country)：
Matching 从独立 Phone Index 查询身份，再按本批身份读取 Facts，校验 messaging.enabled="true"、
合法国家、当前手机号及可选发送国家条件。ANY 只省略国家条件，不放宽消息资格。
此路径不要求 Worker 进入 Messaging Pool，也不通知或删除 Country Pool 中的旧条目。
Kernel 仍只从到期 HOT 获取执行租约，不能抢占正在执行的 Worker；查询资格与执行获取不是同一事务。

切换前结束含旧手机号 Pool 查询／供给声明的 Task，或使用新 scope；不迁移旧 Task/Item，
不保留兼容入口、不自动清理数据。Facts、Phone Index 和历史结果格式保持。

name 是前端生成的显示名称；Task ID 始终由 Server 生成。metadata 保存 scenario=messages、
recipientCountry、可选 senderCountry/senderPhone 和 body。不保存号码列表或统计。
原 Command/Result 的 campaignId 字段使用 Task ID，country 仍表示收件国家。

## 幂等、容量与失败

当前进程保留最多 50 个提交、50000 个收件项的规范化请求及完成结果；同时最多两个新提交。
满时在副作用前返回 429。相同 requestId/内容共享同一次提交与结果，不同内容返回 409。
没有后台提交队列、执行线程或结果观察器。规范化号码和 null/省略 senderCountry 参与幂等。

创建、追加或批准结果不明返回 503 和已知 taskId；TaskCreationUnconfirmedException 保留已生成
身份。不会重新创建、自动重试、删除部分数据或恢复中断提交。全部追加确认后才批准。
关闭先停止新准入，并用共享 5 秒预算等待当前提交；不清理 Redis scope。
幂等不跨重启；重启后 Task 配置、Score 和 Result 仍可按 ID 读取，无需重建 Campaign。

## 数量与结果读取

TaskDataService 使用 Item Score Owner 的 observeItemScoreCounts，最多 100 个 Task，
每 Task 一次只读 Lua 的 ZCARD 和九次 ZCOUNT，批量发送。无 Result 读取、成员扫描或计数保存。
发送总数=成员总数；已发送=6..9；送达=7..9；已读=8..9；已回复=9；当前失败=5。
SENT 不计送达，7→8→9 不重复计数，5→6 可减少失败。部分追加的发送总数可能小于输入数。
读取失败不以零替代；区间计数不是逐成员完整性审计。

Result 预览只有一次 HSCAN COUNT 100，响应截取最多 100 个唯一 ID，再批量读取这些 Items。
COUNT 是提示：原页超出 100 或游标未结束均标 truncated，不补扫、不公开游标。
成功与失败都保留；无法解析或关联不符的内容单列 contentError，保持原执行 resultStatus。
缺少 Item/业务信息仍保留结果行。列表也保留 managed Task 与缺少业务元数据的 Task；
只有明确的消息 Task 才解释发送配置与数量。详情点查项目索引，不从列表前 100 条寻找。

数量、调度状态和内容独立读取，没有共同快照；Score 推进但内容未更新时不互相修复。
Task 结束后仍可 read/reply，Result 查询不删除最新内容；上层不据预览行数计算完成率。

## 模拟收件端与交付

[Worker Simulator](../../worker_simulator_jvm/README.md#messages-and-shared-products) 拥有接收事实、
去重、计划和原 run Reporter 关联。Lab 与 Worker 同进程，但发送/回调都经过实际 HTTP。
delivered 只来自接收；read/reply 可来自 JSON 计划或人工动作；回调排队不表示平台 ACK。
Preview 保留 extension.worker.message.send 的授权协议例外，不支持旧普通文本或 v2 别名。
例如 body 为 `{"receipts_status":["read","replied"],"probability":0.5,"text":"收到了"}`。
计划及 Reporter 不跨 Host 重启恢复，Server 重启可读存量 Task 不等于恢复 Host 计划。

## 页面

启动 [Preview](../../distribution/server/PREVIEW.md#source-launch)，打开 `/messages`，
选择收件国家、发送范围与可选发送号码，导入收件号码并填写 Lab JSON 指令。创建完成后
到 `/messages/tasks/{taskId}` 观察执行与后续回执；不确定提交遵循
[幂等与失败契约](#幂等容量与失败)，不能重新创建来修补未知结果。

[前端 Owner](../../frontend/README.md#messages-business-pages) 维护文件导入、草稿、API/Mock、
手动刷新和有界预览。Campaign 持久化、独立统计存储和自动修复不在本 Owner 内。

## 检查与验收

```powershell
.\gradlew.bat :scenarios:message-campaigns-jvm:test :server_boot_jvm:test
.\gradlew.bat :server_jvm:redisOwnerIntegrationTest :server_jvm:runtimeBoundaryIntegrationTest
.\gradlew.bat :server_boot_jvm:scenarioCompositionIntegrationTest
python integrations/scenario-coexistence/run_proof.py --build --scenario functional
python integrations/scenario-coexistence/run_proof.py --scenario lifecycle
```

[Scenario Coexistence](../../integrations/scenario-coexistence/README.md) 验证实际 Worker 的跨国/ANY、
终态后连续回执、共享 SMS、旧 run 隔离及打包执行。超过 100 个结果使用已知 Item ID 经
results:load 核对，不恢复 UI 分页。固定 1000 Worker 负载保持显式选择，不作本片性能宣称。
Redis Owner 证明展示字段 create-only、创建时间不刷新和数量命令预算；Boot 证明真实部分
追加未确认、迟到执行结果及 Server 重启后直接读取配置和最新回复。
额外的 Messages-only Boot 装配证明不加载 SMS/App Checks API，真实发送完成后仍可送达、
已读及连续回复；外部 Group 绑定和重启保留 managed Task 身份与创建时间。缺失 Group、
事件或 Matching 依赖使启动失败，Project Task 不会被创建，已完成的 Group 注册不回滚。

## Project ownership

场景贡献不可变 Project 和资源需求声明，由 Server 组合配置列表与模块声明并统一准备目录；
场景不直接创建资源，所有新消息任务使用 projectId=messages。重复 Project 声明包括相同
内容均拒绝启动。现有外部配置应删除 `messages` Project 列表项，并设置显式 Group 绑定；
SMS 的现有装配不迁移。Project／Group／managed refill 不变时无需迁移或清理业务数据，
读取也不会补写。改变 Group 是新的资源关联，不会自动搬迁既有 Task 或结果。
