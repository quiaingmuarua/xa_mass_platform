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

```text
创建配置校验 → Server 请求关联创建 → 空的待审核 CLOSE Task → HTTP 201
UTF-8 文件完整校验 → 持有 Task 导入准入 → 每批至多 100 Items → 导入回执
用户核对实际 Item 数量 → 同一准入内校验数量并批准 → 开始发送
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
| `GET /api/v1/messages/catalog` | Project、版本、国家和单次导入限制，不返回进程提交配额或 runId |
| `POST /api/v1/messages/tasks` | 只创建空的待审核 Task，201 返回 `{taskId}` |
| `POST /api/v1/messages/tasks/{taskId}/recipients:import` | `text/plain` UTF-8 文件，返回读取／空行／重复／唯一／确认写入／已存在数量 |
| `POST /api/v1/messages/tasks/{taskId}/approve` | JSON 整数为用户看到的实际数量，核对后批准 |
| `POST /api/v1/messages/tasks/{taskId}/close` | 取消或中止发送调度，保留数据和后续回执 |
| `GET /api/v1/messages/tasks?limit=100` | 创建时间倒序，1..100 个 Task，带 truncated，不分页 |
| `GET /api/v1/messages/tasks/{taskId}` | 项目内 Task、数量及最多 100 条 Result 预览 |

```json
{"requestId":"cross-country","name":"msg-US-CN-example","recipientCountry":"CN","senderCountry":"US","body":"{}"}
```

创建拒绝旧 `recipientIds` 字段。每次导入最多 100,000 个去重收件号码／10 MiB，
Catalog 返回 `recipientsPerImport` 和 `importFileBytes`；多次追加不受旧单任务 1,000 项限制。
UTF-8 校验处理 BOM、空行和首尾空白；号码可省略 `+`，规范化为带 `+` 的 2..15 位数字，
首位非零，CN/US/GB 分别要求 +86/+1/+44 且前缀后有号码。不会自动补国家码或验证真实可达性。
文件内重复跳过，无效号码整批拒绝并报告首个错误行。完整文件通过校验后才分批写入；
上传与规范化临时文件在成功、失败后清理，不持久化源文件或原始行历史。

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

本次输入流程切换保留当前供给／查询结构、Facts、Phone Index 和历史结果格式，
不要求重建已运行的 Task；存量输入版本的管理范围见下文。

name 是前端生成的显示名称；Task ID 始终由 Server 生成。metadata 保存 scenario=messages、
recipientCountry、可选 senderCountry/senderPhone 和 body。不保存号码列表或统计。
原 Command/Result 的 campaignId 字段使用 Task ID，country 仍表示收件国家。

## 幂等、容量与失败

新任务写入 `inputVersion=2`。Server 的 `createForRequest` 以 Project/requestId 关联稳定
Task ID；版本化创建指纹覆盖 Group、name、recipientCountry、senderCountry、senderPhone 和
body。可选字段沿用原归一规则，body 按字符串比较；同身份不同配置冲突，相同配置可跨
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

旧任务保留原身份、Item、有效期和 Result，继续读取、关闭及通用导出；旧输入版本禁止导入和
批准。旧随机 ID 与进程账本不迁移为持久请求关联，不重放旧未确认提交。旧整单创建入口无别名。

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
选择收件国家、发送范围与可选发送号码，填写 Lab JSON 正文。可以创建空任务或创建并导入；
随后在原详情页追加收件人、核对实际数量并启动，或取消／中止。号码输入只显示摘要，后台线程
解析大文件，API 导入仍独立 recheck。创建与导入失败分开处理；不确定创建保留原 requestId 和
载荷，用户显式核对，成功核对不自动导入或批准。已知 Task 上导入失败不得再次创建 Task。

[前端 Owner](../../frontend/README.md#messages-business-pages) 维护会话草稿、API/Mock、
手动刷新和有界预览。页面结构不迁移，本片不新增专用导出、历史分页或持久化导入历史。

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
