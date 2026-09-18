# Messages Task Management

Status: current Messages business owner.

Messages 是 `messages` Project 下有限 Task 的业务视图。与
[SMS Reception](../sms-reception-jvm/README.md) 共享平台、Adapter 和真实 Worker。

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
Task 声明 messaging Pool 供给：worker.country/worker.phone；Item 使用
worker.messaging.available(country/phone)。ANY 同时省略两处国家，仍要求 messaging.enabled。
全部通过既有 Matching 资格与 Kernel 派发，不新增定向获取。

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

`/messages` 和 `/messages/tasks/{taskId}` 共用 MessageTaskSource 下的 API/Mock 组件。
API 不回退 Mock；Mock 明确标识且零网络请求。创建抽屉保留草稿，成功清空并进入详情，
未确认时保留输入和已知 Task 链接，不自动重试。前端生成显示名称，不生成真实 Task ID。
进入页面/手动刷新才读取，无统计定时器；错误保留已知数据。

UTF-8 文件限 1 MiB/1000 号码，支持 BOM、LF/CRLF/CR，浏览器读取，错误保留原草稿及行号。
有限 Task 的通用 10000 行工具保持独立限制。本页面没有分页或导出入口；平台原有成功
Result JSONL 导出能力不变。Campaign 持久化、独立统计存储和自动修复不在本 Owner 内。

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

## Project ownership

Preview profile 声明 messages 及支持的 Group。启动先准备 Group 和 Project managed Task。
场景只消费目录，不注册 Group/Project；所有新消息任务使用 projectId=messages。
无需迁移或清理业务数据；新增展示字段缺失表示未知，读取不会补写。
