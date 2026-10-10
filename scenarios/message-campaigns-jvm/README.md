# Messages Task Management

Status: current Messages business owner.

Messages 承载消息发送及后续送达、已读和连续回复的 tracked 业务过程。
当前发送通过模块声明的 `messages` Project 下有限 Task 提交，Task 结束后仍可接收后续响应；
共享 Group 和环境绑定由 [Boot](../../server_boot_jvm/README.md#platform-and-preview) 提供。

## 归属与流程变化

创建只保存发送配置并返回空的待审核 Task；收件人通过独立 UTF-8 导入追加，用户核对实际
数量后批准。场景不保存提交账本、完整收件人历史或业务结果缓存；Kernel 调度、Matching、
lease、claim、TRACKED 与 Worker/Lab 协议保持原归属。

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

服务保持唯一的业务编排和生命周期准入；`MessageInputs` 承接纯输入规则，
`MessageSpecification` 保留冻结配置、指纹和供给声明，`MessageRecipientFile` 只管理请求级文件材料。
它们使用模块内 `MessageError`，不反向引用服务。`MessageViews` 无状态地解释已读取的
Task、Item Score 和 Result，保留业务关联校验、内容异常与累计阶段计数；它不读取资源、
保存快照或管理生命周期。Controller 的 HTTP 字段、状态码和异常关联信息不变。

Worker 侧发送接入和 Lab 的独立状态归属见
[Simulator Messages 装配](../../worker_simulator_jvm/README.md#messages-and-shared-products)。
两端仍通过现有业务事件协作，不新增跨端协议 JAR，也不让业务场景依赖 Simulator 实现。

```text
创建配置校验 → Server 请求关联创建 → 空的待审核 CLOSE Task → HTTP 201
UTF-8 文件完整校验 → 持有 Task 导入准入 → 每批至多 100 Items → 导入回执
用户核对实际 Item 数量 → 同一准入内校验数量并批准 → 开始发送
Worker message.send → 发送通道确认受理 → SENT 执行结果 → Item 完成／Task 收敛
可选后续回执 → 原 Worker Reporter → best-effort 更新（不等待回执）
  → 既有 Item Score / Result Owner
列表或详情请求 → 读取 Task / Score / Item / Result
```

## Worker 供给与装配

`MessageWorkerSupply` 集中 Project、发送事件、Pool、查询名称和 Worker 资格字段。
严格配置 `xa.mass.scenarios.messages.applications` 是非空有序列表，每项包含 `id`、
`label`、`worker-group-id`；应用 ID 与 Group ID 分别唯一，无隐式默认或旧单 Group 路径。
模块从同一份不可变配置生成 Catalog、一个 `messages` Project 的 Group 列表、
资源需求和创建映射。标签只作展示，不参与创建指纹。

`MessageCampaignsScenarioConfiguration` 注册现有纯数据 `QualifiedCountryDefinition`，
Pool=`messaging`、库存查询=`worker.messaging.available`；Worker Properties 中
`messaging.enabled` 必须精确等于字符串 `true`，国家取 `country`。
声明类型保留的 `worker.messaging.phone` 基础能力不再由 Messages 要求或新建任务使用，
Preview 不启用该函数；不改变 Matching 的 Phone Index、资格规则或声明类型。
这里没有新增 Platform Properties 或属性投影。

每个绑定 Group 都要求 `extension.worker.message.send`、Messaging Pool 和库存查询。
Server 将批量资源需求展开为 Project/Group 检查，在 Group 初始化后、Project Task
初始化前验证；缺失依赖阻止启动。场景不持有资源或补注册事件，宿主管理共享 Group。
普通发送在补给时读 Worker Facts 并按国家入池，消费不再读 Facts；规则与失败边界见
[Matching Owner](../../worker_matching_jvm/README.md#qualified-country-declarations)。

Preview 提供 Demo、App A、App B；App A/B 同时保留 App Checks 能力。
Task 创建时固定一个 Group，ANY 只省略该 Group 内的国家条件，不跨应用调度。
新增 Messaging 能力不改变 App Checks 的事件过滤、窗口投影、Pool 或参数。
实际 Worker 必须安装发送 Handler 并发布合格 Properties；资源声明不证明执行能力。

## API 与业务输入

| API | 契约 |
| --- | --- |
| `GET /api/v1/messages/catalog` | Project、版本 `0.3.0-preview`、有序 applications、国家和单次导入限制；国家不再携带 Group |
| `POST /api/v1/messages/tasks` | 只创建空的待审核 Task，201 返回 `{taskId}` |
| `POST /api/v1/messages/tasks/{taskId}/recipients:import` | `text/plain` UTF-8 文件，返回读取／空行／重复／唯一／确认写入／已存在数量 |
| `POST /api/v1/messages/tasks/{taskId}/approve` | JSON 整数为用户看到的实际数量，核对后批准 |
| `POST /api/v1/messages/tasks/{taskId}/close` | 取消或中止发送调度，保留数据和后续回执 |
| `GET /api/v1/messages/tasks?limit=100` | 创建时间倒序，1..100 个 Task，带 truncated，不分页 |
| `GET /api/v1/messages/tasks/{taskId}` | 项目内 Task、数量及最多 100 条 Result 预览 |

```json
{"appId":"demo","requestId":"cross-country","name":"msg-US-CN-example","recipientCountry":"CN","senderCountry":"US","body":"{}"}
```

创建必须选择已配置的 `appId`，拒绝 `recipientIds`、`senderPhone` 和客户端 `workerGroupId`；未知字段无兼容入口。每次导入最多 100,000 个去重收件号码／10 MiB，
Catalog 返回 `recipientsPerImport` 和 `importFileBytes`；多次追加不受旧单任务 1,000 项限制。
UTF-8 校验处理 BOM、空行和首尾空白；号码可省略 `+`，规范化为带 `+` 的 2..15 位数字，
首位非零，CN/US/GB 分别要求 +86/+1/+44 且前缀后有号码。不会自动补国家码或验证真实可达性。
文件内重复跳过，无效号码整批拒绝并报告首个错误行。完整文件通过校验后才分批写入；
上传与规范化临时文件在成功、失败后清理，不持久化源文件或原始行历史。

requestId/name/appId 最长 128 字符；body 是最长 4096 字符的非空文本。
Messages 不解析 JSON、不裁剪空白、不展开模板变量；导入从 descriptor 读取冻结的配置，
不按当前应用映射重新选择 Group。默认 Simulator 按普通文本发送；只有显式配置为
`lab-json` 的 Worker 才解释 JSON 演示协议并拒绝非法指令。

**收件国家与发送国家独立。** senderCountry 省略/null 为 ANY。
Task 声明 messaging Pool 的 ANY／worker.country 供给，Item 使用
worker.messaging.available 的 ANY／country 查询；供给数量 100，资格始终包含消息能力。
Task 优先级 50、最大重试 3、Item 优先级 5 和 Worker tracked 协议不变。

name 是前端生成的显示名称；Task ID 由 Server 生成。metadata 保存 scenario=messages、
inputVersion=3、appId、recipientCountry、可选 senderCountry 和原始 body。
不保存号码列表或统计。Command/Result 的 campaignId 使用 Task ID，country 仍表示收件国家。
列表及详情返回保存的 appId 和实际 workerGroupId；旧任务缺少 appId 时不重建应用身份。

## 幂等、容量与失败

新任务写入 `inputVersion=3`。Server 的 `createForRequest` 以 Project/requestId 关联稳定
Task ID；`messages/v3/create` 指纹覆盖 appId、解析后的 Group、name、recipientCountry、senderCountry 和
原始 body。可选字段沿用原归一规则，body 按字符串比较；同身份不同配置冲突，相同配置可跨
Server 重启核对，保证限于原 Task 资源保留期间。数据不完整返回未确认及固定身份，不修复。

导入从已保存的 descriptor 取得固定配置。messageId 为 `message-` 加 SHA-256，输入是
长度前缀 UTF-8 的 `["messages/v2/message", taskId, normalizedRecipient]`。同任务内收件人
唯一，跨任务可以再次发送；该作用域也避免 Lab 的全局 messageId 去重误伤另一个任务。
通过 Server `importFiniteTaskItems` 按每批最多 100 项追加，使用 Runtime 默认有效期
（当前 365 天），删除场景内固定 60 秒 TTL。重传跳过内容一致的完整 Item，不刷新时间、
有效期或参数；缺失 Score 的已存 Item 仅通过现有 Owner 操作使用原值补齐。内容冲突拒绝
覆盖，其他不一致返回不可用。导入去重不扩大外部发送和 Worker 的交付保证。

整个文件导入与批准、关闭、普通追加复用同一个 `OperationGuard.taskMutation`，保证限定
为单 Server 实例。同时最多两个导入，超出时在文件读取前返回 429；结束即释放，无累计配额。
部分写入后失败不回滚，回执或错误返回 taskId、confirmedAddedCount 和 existingCount；未知
部分不计为拒绝，不自动重传或启动。用户可显式重传，或按 Owner 当前实际数量批准已导入部分。
参数／状态／内容冲突为 409，输入无效为 400，文件过大为 413，未确认结果为 503。

批准必须处于新版本待审核状态，实际数量大于零且与请求一致；读数与批准在同一次准入内。
关闭保留已有结果和未发送数量，终态不能重启；已经发送的消息仍可通过原 Reporter 返回回执。
服务关闭停止新准入，以共享 5 秒预算等待在途写操作，导入停止后续批次；不清理 Redis scope。

旧任务保留原身份、Item、有效期和 Result，继续读取、关闭及通用导出；非 v3 输入禁止导入和
批准。历史 senderPhone 只读，旧定向任务不改写成 Pool 任务。旧随机 ID 与进程账本不迁移为持久请求关联，不重放旧未确认提交。旧整单创建入口无别名。

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

[Worker Simulator](../../worker_simulator_jvm/README.md#messages-and-shared-products) 拥有模拟发送端
受理、窗口去重、可选观察和原 run Reporter 关联。发送与回调经过实际 HTTP，但不证明第三方交付。
默认 `text` 原样受理并返回 SENT，不自动生成回执；`lab-json` 才解释演示 JSON。
回执关联或观察容量不足不拒绝发送，held／回调溢出不触发补发；发送并发和去重容量仍独立背压。
去重与关联默认保留 10 分钟，重传／回复不续期。窗口到期或 Host 重启不保证模拟端去重，
也不恢复计划和 Reporter。Item 的 Runtime 默认有效期保持独立。Task 结束后，窗口内原 Reporter
仍可上报；Worker 停止清理本地观察，Server 已有 Result 保留。
稳定 messageId 只提供输入身份，不承诺外部发送 exactly-once。完整字段、容量、清理与故障边界
统一由 Simulator Owner 维护，Messages 不保存另一份模拟端账本。

## 页面

[前端 Owner](../../frontend/README.md#messages-business-pages) 维护任务列表、统一操作菜单、
结果抽屉和单页创建。应用来自真实 Catalog；选择应用、收件国家、发送范围和 Template content，
可创建空任务或创建并导入，随后独立审核启动。内容默认留空，Lab JSON 示例位于折叠帮助，注明仅适用于显式配置的演示 Worker。
创建身份核对、导入恢复、手动刷新和最多 100 条预览沿用原边界。

本片使用新的 Redis scope 和新的 Simulator inventory，不迁移旧任务、Group 或 Worker 文件。
沿用 [Preview 启动入口](../../distribution/server/PREVIEW.md#messages-multi-application-cutover)，
不自动重启当前实例，不用新配置覆盖旧 Group；旧未确认提交不得在新环境自动重放。

## 检查与验收

```powershell
.\gradlew.bat :scenarios:message-campaigns-jvm:test :server_boot_jvm:test
.\gradlew.bat :server_jvm:redisOwnerIntegrationTest :server_jvm:runtimeBoundaryIntegrationTest
.\gradlew.bat :server_boot_jvm:scenarioCompositionIntegrationTest
python integrations/scenario-coexistence/run_proof.py --build --scenario functional
python integrations/scenario-coexistence/run_proof.py --scenario lifecycle
```

[Scenario Coexistence](../../integrations/scenario-coexistence/README.md) 验证三应用实际 Pool Worker 的跨国/ANY 与 Group 隔离、
显式 JSON 模式的输入拒绝、文本无回执完成、终态后连续回执、共享 SMS、旧 run 隔离及打包执行。
同 Worker/原 Reporter 定点证据使用通用 Task/workerId，业务创建不再提供定向入口。超过 100 个结果使用已知 Item ID 经
results:load 核对，不恢复 UI 分页。固定 1000 Worker 负载保持显式选择，不作本片性能宣称。
Redis Owner 证明展示字段 create-only、创建时间不刷新和数量命令预算；Boot 证明真实部分
追加未确认、迟到执行结果及 Server 重启后直接读取配置和最新回复。
额外的 Messages-only Boot 装配证明不加载 SMS/App Checks API，真实发送完成后仍可送达、
已读及连续回复；外部 Group 绑定和重启保留 managed Task 身份与创建时间。缺失 Group、
事件或 Matching 依赖使启动失败，Project Task 不会被创建，已完成的 Group 注册不回滚。

## Project ownership

场景贡献不可变 Project 和资源需求声明，由 Server 组合配置列表与模块声明并统一准备目录；
场景不直接创建资源，所有新消息任务使用 projectId=messages。重复 Project 声明包括相同
内容均拒绝启动。现有外部配置应删除 `messages` Project 列表项，并设置显式 applications 绑定；
SMS 的现有装配不迁移。Project／Group／managed refill 不变时无需迁移或清理业务数据，
读取也不会补写。改变 Group 是新的资源关联，不会自动搬迁既有 Task 或结果。

十万号码导入用例不证明发送。现有 Product Coexistence runner 的 `messages-send-100k`
手动 workload 实际执行 105,000 条发送、51 个 Task，持续独立核对发送端受理指纹、完整 Result
和 Item 状态，不等待回执、不以预览或保留历史代替全量证据。该证明与原 12,000 条混合回执
负载分开报告，均不建立生产吞吐 SLA。
