# 按现象排障

Status: current bounded-observation troubleshooting guide.

先按[接管路线](deploy.md#release-preview)确认环境与版本，再记录失败时间和已有 Task／Item／Worker 身份。
观察页是有界视图，优先点查已知 ID；某页没有条目，不等于全库不存在。

## 从现象选择下一步

| 现象 | 观察入口 | 如何判断 | 下一步与交接证据 |
| --- | --- | --- | --- |
| 服务不可用或未 Ready | 实际服务状态、启动日志、readiness；本地看启动终端 | 分清进程退出、依赖不可用和启动配置错误 | 按[部署](deploy.md)处理明确错误；保留版本、退出原因、依赖及错误码 |
| 页面打不开或环境不对 | 管理入口、安装 commit、静态目录及 API／Mock 模式 | Mock 不代表真实 Server；随包文档属于该安装版本 | 对照环境记录与地址；提供请求路径、HTTP 状态及构建身份 |
| 业务页面不可用 | 当前身份的 Catalog、Scenario enabled 和启动依赖日志 | 缺装配／配置与缺授权需分别处理 | 查[配置归属](configuration.md)，不伪造 Catalog；记录 profile、Project 与错误 |
| API 返回 401 | Console 登录、请求 Authorization、管理员目录中的密钥状态 | 缺失、格式错误或已清除的访问密钥不能准入 | 以有效密钥重新登录；脚本使用 Bearer，只报告状态不粘贴密钥 |
| API 返回 403／看不到他人任务 | 当前用户、角色、Project 授权与 Task 所有者 | 登录成功不等于有该操作权限 | 读取当前授权后处理；记录操作与归属，不用更换 Task ID 绕过 |
| Worker 无法接入／反复断开 | Host 日志、HTTPS/WSS endpoint、网关认证、Worker 详情“接入控制” | 区分凭据拒绝、冷却、连接故障与 Host 已停止 | 依下文核对冷却或显式恢复；保留原身份、错误码和时间 |
| Worker 已连接，任务不执行 | Task 审核状态、实际 events snapshot、Group 资源及 Properties | 连接、能力、资格与实际执行不是同一状态 | 按[执行链](#不执行沿链检查)定位；提供已知 Task／Worker 与最早失败环节 |
| 配置保存后行为不变 | “已保存”／“当前生效”配置、启动参数及 Task 创建时间 | 保存未重启，或旧 Task 仍使用首次快照 | 按[生效规则](configuration.md#配置已保存为什么行为没变)处理，记录配置指纹；不覆盖 seed 掩盖问题 |
| Batch Checks 无法启动 | 任务的数量／费用确认与原启动请求状态、所有者余额 | 余额不足、明确拒绝和扣分未确认含义不同 | 保留原 requestId 核对／继续；提供状态、数量和金额，不重复新建扣费 |
| Runtime 结果已不可读 | Task 保留阶段、historyId 与显式 Storage 入口 | RELEASED 后 Runtime 拒绝载荷读取；有效历史可独立读取 | 按[数据说明](configuration.md)切换历史；记录数据源、阶段和期限 |
| RPC 或 SMS 空结果 | 原 messageId、调用类型、已取得回执及保留期限 | RPC take 已消费、回收和 SMS 内存快照丢失均不等于从未执行 | 不重新提交旧调用；保留原回执，查[工作台](../usage/workbench.md)／[SMS](../usage/sms.md#保留边界) |
| 操作或 cleanup 显示未确认 | 原 requestId／Run ID、状态查询及对应操作记录 | 未确认不等于失败，也不能按成功覆盖 | 接回同一操作、收取原证据；明确未知范围后再决定下一步 |

## “不执行”沿链检查

1. **准入和工作状态**：确认明确响应；有限任务已导入并审核，RPC Task 已准备，终态不重开。
2. **实际能力**：Group `eventCodes` 是声明；用 `platform.worker.events.snapshot` 查看 Worker 真实安装集合。
3. **候选条件**：核对 Group、Pool/query、供给、Properties 与窗口绑定；配置存在不保证立刻有库存。
4. **取得执行权**：候选仍需 Kernel 当前 Worker／Item 准入；可能正在占用、恢复或已过期。
5. **投递与结果**：区分 Adapter 投递、Handler 拒绝／异常、等待超时和业务结果解释。

持续待处理工作与持续空闲、兼容的 Worker 在多轮合格条件下仍未执行，才沿
[调度活性](../../kernel/scheduling-overview.md#scale-and-liveness)交给对应开发 Owner。
一次 Direct Call 取得回复只验证该次定向调用，不证明调度健康。

## 管理 Worker 接入冷却

管理员进入 Runtime Workers，打开指定 Group／Worker 的详情和“接入控制”。
先读取当前状态，再选择未来截止时间设置冷却，或显式解除。该操作立即撤销
WebSocket 路由，不等待忙碌 Handler 完成；已发生的业务副作用不能撤销。
不支持的 Transport 或不由当前 Runtime 管理的 Adapter 会明确拒绝修改。

冷却状态、网络连接、调度暂停和 Pool 资格是不同事实。解除冷却不会恢复 PAUSE，
也不会改变 Pool 规则。保存／断连未确认时保留原请求，读取核对后显式重试；
遇到更新的修订时不能用旧请求覆盖，放弃本地核对也不撤销 Server 已执行的操作。

Android 进程存活时约每小时检查一次；退出后没有定时任务，下次启动会重新检查。
页面手动“尝试连接”可以立即核对，手动停止会取消本进程的自动检查。
Java 对应实例保持停止，需显式启动；其他实例不受影响。
详细边界见 [Server 接入冷却](../../../server_jvm/README.md#worker-connection-cooldown)。

## 结果不一致与恢复

Result 内容与 Item 状态独立维护。Messages 在 Task 调度结束后仍可收到后续观察；
Batch Checks 协议内容错误属于检查错误；有限历史、RPC 消费与回收各自有保留语义。
先确认数据源、时间和查询类型，再判断是否异常，不用预览样本推算全量。

维护后 Host 存活但 Worker runtime STOPPED 时，按[恢复入口](deploy.md#排障与恢复)核对原身份和目标版本步骤。
不批量重启其他进程、生成新 inventory、清空数据库或更换请求 ID 来掩盖原问题。

## 提交给开发者的最小证据

提供版本／commit、环境与配置坐标、有限的身份集合、时间、操作顺序、HTTP 状态及模块错误码，
并说明在哪一层已确认、哪一步失败或未知。附相关进程的有界日志片段；错误码查 Console 的 Diagnostic Codes。
沿[开发入口](../development/index.md)交给负责 Owner，不要求先收集全库或完整 fleet。

不附密钥、完整 opaque payload/result。源码存在、一次检查通过与当前线上健康分别表述；
后续修改按[验证入口](../verification.md)选择 proof。
