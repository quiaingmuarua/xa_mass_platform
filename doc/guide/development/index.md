# 开发与 Agent 入口

Status: current task-oriented development navigation.

先确认当前 Git HEAD、工作区改动和用户要求；保留无关修改。
仓库变更规则由 [AGENTS](../../../AGENTS.md)维护，下面按任务定位读取范围，
不要求每次修改都重新通读项目。

## 选择阅读路线

| 开发任务 | 从哪里开始 | 继续读什么 |
| --- | --- | --- |
| 第一次整体交接 | [仓库摘要](../../../README.md) → [完整行为主线](../../kernel/scheduling-overview.md) | 状态 Owner、主调用链、失败边界、代码与 proof 入口明确后停止扩展 |
| 局部修复 | 对应 Owner 的受影响章节与修改点 | 直接调用者、消费者、最近的断言和相应验证 |
| 新 Worker 能力 | [接入走读](walkthrough.md) → [Worker Core](../../../transport/worker-core/README.md#message-path) | Host 安装、Group 声明、场景适配及实际执行证明 |
| 新 Batch Checks 协议或配置 | [场景 Owner](../../../scenarios/app-checks-jvm/README.md#具名执行契约) | 纯契约、配置解析、Task 保存快照、Host 绑定和前端结果 |
| 修改 Pool/query/Properties | [Matching Owner](../../../worker_matching_jvm/README.md) | qualification/refill、消费、失败规则及实际调用者 |
| 修改调度、租约、重试或恢复 | [Kernel 索引](../../kernel/README.md)与 [Pacer](../../../kernel_pacer_jvm/README.md) | 受影响主线、机械 Owner、Policy、装配与 Redis proof |
| 修改 API 或权限 | [Server Owner](../../../server_jvm/README.md#api-reference-and-response-contract) | DTO/准入服务、caller、前端与 OpenAPI snapshot |
| 修改启动或交付 | [Boot](../../../server_boot_jvm/README.md)、[Distribution](../../../distribution/server/README.md) | 实际 profile、源码/ZIP launcher、archive checks |
| 修改页面 | [Frontend Owner](../../../frontend/README.md) | route、source/API、Mock、访问与操作状态 |

“局部”修改如果改变其他 Owner 的输入、状态、调用次序或生命周期，应扩展到对应跨模块
链路。阅读结束的标准是影响已经闭合，而不是打开过多少文件。

## 对一次修改回答五个问题

1. 输入来自哪里，由谁校验？
2. 哪个 Owner 可以写入目标状态，真实源码入口是什么？
3. 谁消费新状态，调用次序与失败窗口是什么？
4. 需要同步修改哪些调用者、配置、用户说明和 Owner 章节？
5. 哪个现有 proof 拥有变化的 claim，哪些结论仍需运行才能确认？

使用 [主线的代码与断言指针](../../kernel/scheduling-overview.md#production-and-proof-pointers)
定位后继续追真实 caller，不能把类名列表当作证明。
不需要为每次修改创建新 roadmap、Owner registry 或额外接口层。

## 验证与交付说明

先做受影响 Owner 的 focused tests；Redis 原子性使用真实 Redis Owner lane，
协议与进程边界使用对应 Boundary/Integration lane。
通过[验证入口](../verification.md)和 [TESTING](../../../TESTING.md#selection-decision)选路。

提交前检查 diff、受影响引用、文档链接，以及旧名称是否仍被当成现行入口。
完成说明至少写清行为变化、实际运行的检查、未运行的必需证明及证据限制。
源码阅读、测试存在、CI 被选中、某个 commit 的 CI 通过是不同事实。

## 维护文档

Human 指南解释任务步骤、概念和排障路径；Owner 维护精确字段、状态、存储和生命周期；
Proof Registry 维护 claim/nonclaim；TESTING 维护命令与选路。
文档站只是这些源文件的发布视图。

行为变化在同一改动中更新 Owner；当用户步骤或入口改变时同步改指南。
只改说明不应为了匹配 prose 改生产行为。历史记录保留固定版本身份，
不能被当前导航、搜索或 Agent 当作现行约束。
