# 使用任务工作台与 Direct Debug

Status: current generic Task and direct-call user guide.

管理员通过 `/runtime/tasks`（含 `RPC 任务`、`有限任务` 与 `全局调度` 视图）使用平台通用能力。已经有专门业务入口时，先使用
[Batch Checks](batch-checks.md)、[Messages](messages.md)或 [SMS](sms.md)，
让对应场景承担输入转换和结果解释。

## 选择执行方式

| 需求 | 入口 | 结果如何读取 |
| --- | --- | --- |
| 一次导入一批工作，审核后执行 | 有限 Task 工作台 | Runtime 进度和结果；历史发布后显式切换 Storage |
| 持续向同一明确 Project/Group 提交调用 | RPC 工作台 | 显式准备可复用 Task，再提交调用或按原 ID 消费结果 |
| 已知道 Worker/Adapter，只调试一次本地能力 | Worker 观察页的 Direct Debug | Server 关联的一次回复；无持久 Result 查询保证 |

Direct Call 不创建 TaskItem、不取得 Kernel 调度租约，也不提供任务重试。
超时只表示本次等待没有获得回复，不能证明 Handler 没有执行。
需要进度和调度推进的业务使用 Task 路径。

## 通用 Task 的准备顺序

1. 选择当前身份可访问的 Project，查看其 Group 与能力。
2. 确认目标 Group 已注册、对应 Worker 的 Handler 已安装。
3. 明确事件完整名称、输入格式和 Worker query。只有使用 Pool 时才配置相应供给；
   按 `workerId` 直接定位不需要 Pool。
4. 有限任务先创建、追加 Item，再核对实际数量批准；RPC 先显式准备 Task，再发起调用。
5. 保留 Task ID、messageId 和请求回执，按对应结果读取接口核对。

Group catalog 的事件声明不是已加载 Handler 的证据；可通过
`platform.worker.events.snapshot` 查看实际 Worker 进程的安装结果。
Project 绑定控制准入，不会创建 Task、Pool 或 Worker。

## 有限任务的 Runtime 与 Storage

Runtime 页面用于查看当前任务及执行操作；“历史任务”入口用于读取已经发布的有限 Task
快照。在工作台选中 Project 和“有限任务”后点击“历史任务”，路由保留该 Project，
并带上 `tab=finite&view=history`。Batch Checks 和 Messages 也有各自的历史入口。

1. Task 终止后先查看保留提示。冻结和发布尚未完成时，不能把它当作可用历史。
2. 页面显示 Runtime 输入/结果已释放后，点击历史链接，或进入历史列表按原 Task ID 查找。
   Runtime 此时仍可保留状态和数量，结果预览与导出改从显式 Storage 入口读取。
3. 在历史页分页查看保存的输入、独立 Item 状态和结果；原始成功结果可完整导出 JSONL。
   “返回 Runtime”只切换数据源，不恢复输入、执行或管理操作。

历史列表是当前 scope 内、按权限过滤的分页快照，Runtime 列表仍是有界载入窗口。
历史详情保留冻结时的描述和数量，不依赖当前 Group catalog，也不提供批准、关闭或重跑。
没有 Result 的输入不会被补成失败；预览不能代替完整导出。

Runtime 清理后，已发布且未到期的历史仍可读取；历史也到期后将不可用，刷新或下载不会续期。
以页面显示的首次终止、发布时间和到期时间为准，配置与精确规则见
[有限 Task retention](../../../server_jvm/README.md#finite-task-retention)和
[Storage API](../../../server_jvm/README.md#finite-task-history-api)。
历史快照不记录完整导入/操作过程。Mock 历史只在会话内演示同类视图，不是真实持久化记录。

## 使用当前接口说明

真实 Server 的 `/scalar` 提供当前运行 API；Console `/api-reference` 的静态快照
用于浏览，不能替代当前 Server 的实际准入校验。以当前运行版本的 DTO、Owner 和错误
响应为准，不依据旧 API 示例构造兼容字段。

## RPC 结果读取与回收

RPC 工作台调用的 `items:call` 和后续 `results:take` 会消费取得的内容，
应保留返回值并用原 messageId 核对未知状态。消费可能在响应丢失前已经完成，
重读不能找回已取走的内容。`results:load` 仍是非消费观察，可用于诊断。

| 路径 | 对数据的影响 |
| --- | --- |
| 消费读取 | 原子取走本次已有 Result；不结束 Item、不改变 Score，也不阻止后续合法结果 |
| Result idle TTL | 实际 RPC Result 写入刷新整个 HASH 的期限；读取、消费和提交不续期，空闲过期不清理 Item/Task |
| 可选终态 Item 回收 | 默认关闭；显式启用后按 Owner 的资格与两阶段时间条件释放输入、清理 Result/Score；不生成 Storage 历史 |

`not_observed` 可能表示尚未观察到、已经消费或已经过期/清理，不能据此断定 Handler 没有执行。
启用终态回收后，旧 RPC 输入可以返回 null，最终清理也会使保留的 Item 数量下降；
数量不是累计调用总数。Score 记录删除后同 ID 去重保证结束，过期旧调用不要自动重放，
新业务调用使用新 messageId。部署启用方式见
[RPC 终态数据保留](../operations/deploy.md#rpc-终态数据保留)。

有限 Task 的保留和导出使用另一套生命周期，不能套用 RPC 的消费语义。
详情见 [Server Task 与 RPC](../../../server_jvm/README.md#projects-and-explicit-rpc-tasks)、
[结果消费](../../../server_jvm/README.md#rpc-result-consumption-and-idle-reclamation)及
[Frontend 工作台](../../../frontend/README.md)。
