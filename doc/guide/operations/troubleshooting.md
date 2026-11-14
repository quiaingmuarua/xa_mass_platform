# 按现象排障

Status: current bounded-observation troubleshooting guide.

先记录当前版本、Server 地址、Redis scope、profile、Task/Item/Worker 身份和失败时间，
再从最靠近现象的入口查起。一次检查只确认一个事实；“连接存在”“有 Handler”
“匹配合格”“取得租约”“已执行”“有结果”分别成立。

## 常见现象

| 现象 | 先检查 | 下一步 |
| --- | --- | --- |
| 启动失败或没有 Ready | 启动终端、实际日志目录、依赖工具、端口、Redis 和配置文件错误 | 修复明确错误再重启本次环境；见[部署](deploy.md) |
| 页面打不开或打开了错误环境 | Ready 地址、静态资源目录、普通/API 与 Mock 模式 | 对照最终坐标；Mock 页面不能证明 Server 已启动 |
| 业务页显示不可用 | 该环境的 Catalog、Scenario enabled 与启动依赖错误 | 检查宿主装配，不先改浏览器缓存或伪造 Catalog |
| 401 | 访问密钥缺失、格式错误或已被清除 | 用有效密钥重新登录；脚本补 `Authorization: Bearer <access key>` |
| 403，或列表缺少别人的任务 | 当前用户、角色、Project 授权、任务所有者 | 刷新/重新登录；授权范围不等于系统故障 |
| 新配置保存后没生效 | 保存文档、当前生效文档、是否重启、是否旧 Task | 按[配置](configuration.md)核对，不重复覆盖 seed |
| Worker 已连接，但任务不执行 | Task 是否已审核、Group/event、查询和 Pool 供给、实际属性 | 逐项区分准入与资格；查看下面的执行链 |
| Worker 被断开且无法重新接入 | 管理员在 Worker 详情中读取“接入控制”，区分冷却、认证错误和暂时不可用 | 冷却到期或解除后仍需客户端再次检查；不要用调度恢复替代解除冷却 |
| Batch Checks 无法启动 | 实际 Item/种子数、固定单价、所有者余额、原费用请求状态 | 先核对原请求；余额不足与启动结果未知采用不同恢复方式 |
| 有执行但没看到结果 | 原 Task/messageId、查询方式、预览截断、业务 contentError | 保留身份，分别核对原始 Result 与业务解释 |
| SMS 重启后原 ID 空结果 | 已消费结果、Server 短期缓存、是否保留过 HTTP 回执 | 空读取不等于曾经没有结果；见 [SMS 保留边界](../usage/sms.md#保留边界) |

## “不执行”沿链检查

1. **请求是否准入**：确认明确 HTTP 状态、错误码及原请求是否已知成功。
2. **工作是否可调度**：有限任务要先导入再批准；终态不重开。RPC Task 需要显式准备。
3. **能力是否安装**：Group `eventCodes` 仅为目录。检查实际 Worker 的
   `platform.worker.events.snapshot`，或针对已知 Worker 做一次有界调试。
4. **查询是否有候选**：核对 Group、Pool/query、供给声明、所需 Properties 和窗口绑定。
   配置资源并不保证立刻有库存。
5. **执行资格与领取**：Matching 候选仍需 Kernel 当前 Worker/Item 准入。
   Worker 可能占用、恢复中或候选已过期；不要在 Server 侧补一个“换 Worker”循环。
6. **投递与执行**：区分 Adapter 投递失败、Handler 拒绝、执行异常和本次等待超时。

Runtime 页面是有界观察。某页没有 Worker 或 Result，不等于全库不存在；
优先使用已知 ID 点查。不要把 Properties Preview 的样本当作完整 fleet 清单。
持续待处理工作与持续空闲、兼容的 Worker 在多轮合格条件下仍无法匹配，才值得沿
[调度活性主线](../../kernel/scheduling-overview.md#scale-and-liveness)继续定位。

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

## 结果不一致时

Result 内容与 Item Score 独立维护，读到内容不能证明终态已经更新。
Messages 的 Task 结束后还可能接收已读/回复；Batch Checks 的无效协议内容属于检查错误；
RPC 消费与有限结果归档又是不同路径。先确认查询类型，再判断应不应该继续有内容。

操作显示“未确认”时，先保留原 requestId、Task ID 和 messageId 做核对。
更换请求 ID、重复导入、重复扣分或直接删除数据，都可能遮蔽原本可恢复的证据。

## 提交给开发者的最小证据

提供版本/commit、环境坐标、有限的身份集合、发生时间、操作顺序、HTTP 状态和模块错误码，
以及相关进程的有界日志片段。错误码目录位于 Console 的 Diagnostic Codes 页面；
完整机制问题沿 [开发入口](../development/index.md)交给对应 Owner。

不记录完整 opaque payload/result，不执行全库清空，不把一次 Direct Call 成功写成
调度健康证明。修改行为后，根据[验证指南](../verification.md)选择对应 proof。
