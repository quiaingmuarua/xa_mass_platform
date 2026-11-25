# 使用 Messages

Status: current Messages user guide.

Messages 创建有限发送任务，并继续接收发送后的送达、已读和回复观察。
Preview 使用模拟收件端；Android 示例使用设备上的本地消息交互。
这些示例不提供真实运营商短信投递。

## 创建和发送

1. 登录有 `messages` Project 授权的身份，打开 `/messages`，点击创建入口。
2. 选择 Catalog 中的应用、收件国家和发送国家范围，填写 Template content。
3. 创建空的待审核任务，或创建时同时导入收件号码。
4. 查看实际收件人数量与保存的正文，再独立审核启动。
5. 手动刷新 Task 进度和结果；可关闭进行中的任务，已保存的结果仍按保留规则读取。

正文原样传给接收 Event，不由页面解析 JSON 或展开模板变量。
Preview 默认按普通文本发送；折叠帮助中的 Lab JSON 只适用于显式配置为
`lab-json` 的演示 Worker。不要因正文看起来像 JSON 就推定会自动产生回执。

收件号码使用 UTF-8 TXT 或粘贴，每行一个带国家码号码，可省略开头的 `+`。
导入会进行国家前缀和格式校验、文件内去重；单次限制和错误语义见
[Messages 输入契约](../../../scenarios/message-campaigns-jvm/README.md#api-与业务输入)。

## 正确读取进度

| 观察 | 能说明什么 |
| --- | --- |
| 已发送 / SENT | Handler 已完成发送受理；不等于对方已收到 |
| 送达 | 后续送达观察已返回 |
| 已读 | 后续已读观察已返回 |
| 已回复 | 已有回复观察，业务视图保留最新回复 |
| Task 调度结束 | 当前任务不再继续调度；冻结前的收尾窗口内仍可能保存后续业务观察 |

发送、送达、已读、回复是累积阶段，不能把它们相加当作总数。
任务统计与结果内容独立读取；预览最多展示有界结果，不能据此计算整个任务的比例。
当前列表也是载入窗口，筛选与计数不等于全库扫描。

Preview 的收件交互在独立 Host `/lab` 页面；Android 本地交互见
[Android 消息示例](../../../distribution/server/src/main/dist/examples/messages/README.md)。
Mock 的显式推进按钮仅演示 UI 状态，不执行真实发送。

## Runtime 与历史结果

Runtime 详情展示当前累计结果。有限 Task 终止后，Server 按保留策略冻结并发布历史，
随后释放 Runtime 输入与结果；冻结后的历史不会继续吸收新送达、已读或回复观察。
需要保留哪些业务观察，应按冻结边界评估，不能把历史保留期当作继续接收回执的窗口。

页面提示已释放时，管理员通过历史链接进入 `/storage/tasks?project=messages`；
operator 继续通过 `/messages?view=history` 按原 Task ID 查找自己的历史。
历史页按保存的 Task 正文、应用/Group 绑定和结果进行只读观察，并提供分页输入与独立状态。
它不重建完整回复、导入或操作历史，也不提供再次发送；当前应用映射变化不改写保存的归属。
业务预览与原始成功 Result JSONL 导出分开，Messages 没有业务 CSV 导出。

已发布历史在 Runtime Task 清理后仍可读到自己的到期时间；读取不续期。
共同步骤和权限边界见[Runtime 与 Storage](workbench.md#有限任务的-runtime-与-storage)，
精确冻结与观察规则见 [Messages Owner](../../../scenarios/message-campaigns-jvm/README.md)。

## 未确认和配置变化

创建、导入发生响应丢失时，用原请求身份核对。已有 Task ID 后，导入失败应继续处理
原任务，不能通过再次创建绕过核对。批准时使用重新读取的实际数量。

应用到 Group 的映射在创建时解析并存入 Task；后续配置变更不会把旧任务迁到新的 Group。
权限撤销影响后续请求，不改变已保存的创建者。
完整状态、保留、幂等和证明边界属于
[Messages Owner](../../../scenarios/message-campaigns-jvm/README.md)。
