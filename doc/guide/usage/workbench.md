# 使用任务工作台与 Direct Debug

Status: current generic Task and direct-call user guide.

管理员通过 `/task-workbench` 使用平台通用能力。已经有专门业务入口时，先使用
[Batch Checks](batch-checks.md)、[Messages](messages.md)或 [SMS](sms.md)，
让对应场景承担输入转换和结果解释。

## 选择执行方式

| 需求 | 入口 | 结果如何读取 |
| --- | --- | --- |
| 一次导入一批工作，审核后执行 | 有限 Task 工作台 | Task 进度、原始 Result 预览和完整导出 |
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

## 使用当前接口说明

真实 Server 的 `/scalar` 提供当前运行 API；Console `/api-reference` 的静态快照
用于浏览，不能替代当前 Server 的实际准入校验。以当前运行版本的 DTO、Owner 和错误
响应为准，不依据旧 API 示例构造兼容字段。

RPC 工作台调用的 `items:call` 和后续 `results:take` 会消费取得的内容，
应保留返回值并用原 messageId 核对未知状态。`results:load` 仍是非消费观察，
可用于诊断，但不会恢复已经取走的结果。
有限 Task 的保留和导出使用另一套生命周期，不能套用 RPC 的消费语义。
详情见 [Server Task 与 RPC](../../../server_jvm/README.md#projects-and-explicit-rpc-tasks)、
[结果消费](../../../server_jvm/README.md#rpc-result-consumption-and-idle-reclamation)及
[Frontend 工作台](../../../frontend/README.md)。
