# 开发与 Agent 入口

Status: current task-oriented development navigation.

先确认当前 Git HEAD、工作区改动和用户要求；保留无关修改。
仓库变更规则由 [AGENTS](../../../AGENTS.md)维护，下面按任务定位读取范围，
不要求每次修改都重新通读项目。

日常开发的默认路径是：**代码与 Owner → 本地针对性验证 → 受影响 CI → PR 交付**。
Android Worker、认证和公网连接能力也遵循这条路径；需要真实链路时，使用本地
Server／Adapter、设备及测试网关或现有 CI fixture。GCP 地址、凭据和主机状态不是
开发前提。项目理解和代码 review 默认不读取线上记录；只有任务明确包含部署、
现场诊断或公网验收时，才进入下面的环境维护路线。

## 选择阅读路线

| 开发任务 | 从哪里开始 | 继续读什么 |
| --- | --- | --- |
| 第一次整体交接 | [仓库摘要](../../../README.md) → [完整行为主线](../../kernel/scheduling-overview.md) | 状态 Owner、主调用链、失败边界、代码与 proof 入口明确后停止扩展 |
| GCP／Release Preview 维护 | [环境定位与交接](../operations/deploy.md#release-preview) → 只读核对环境记录 | 确认实际版本、配置坐标及已完成／待补验收；需要升级才读目标版本说明，需要修代码才进入对应 Owner |
| 局部修复 | 对应 Owner 的受影响章节与修改点 | 直接调用者、消费者、最近的断言和相应验证 |
| 新 Worker 能力 | [接入走读](walkthrough.md) → [Worker Core](../../../transport/worker-core/README.md#message-path) | Host 安装、Group 声明、场景适配及实际执行证明 |
| 新 Batch Checks 协议或配置 | [场景 Owner](../../../scenarios/app-checks-jvm/README.md#具名执行契约) | 纯契约、配置解析、Task 保存快照、Host 绑定和前端结果 |
| 修改 Pool/query/Properties | [Matching Owner](../../../worker_matching_jvm/README.md) | qualification/refill、消费、失败规则及实际调用者 |
| 修改调度、租约、重试或恢复 | [Kernel 索引](../../kernel/README.md)与 [Pacer](../../../kernel_pacer_jvm/README.md) | 受影响主线、机械 Owner、Policy、装配与 Redis proof |
| 修改 API、认证或用户权限 | [Server 响应合同](../../../server_jvm/README.md#api-reference-and-response-contract)与[认证/用户目录路径](#认证与用户目录) | DTO、请求 caller、目录发布、前端与 OpenAPI snapshot |
| 修改有限 Task 保留或历史查询 | [有限任务历史路径](#有限任务保留与-storage) | 冻结/发布/释放、独立 Storage 准入、场景解释及页面切换 |
| 修改 RPC 消费或回收 | [RPC 数据路径](#rpc-消费与回收) | 分开确认 take、Result idle TTL 和可选终态 Item 回收 |
| 修改 Worker 连接冷却 | [连接冷却路径](#worker-连接冷却) | Server 决策、Adapter 身份准入/撤路由、SDK run 生命周期 |
| 修改启动或交付 | [启动与交付路径](#启动与交付) | 实际 profile、业务依赖、源码/ZIP launcher 与归档消费断言 |
| 修改页面 | [Frontend Owner](../../../frontend/README.md) | route、source/API、Mock、访问与操作状态 |

“局部”修改如果改变其他 Owner 的输入、状态、调用次序或生命周期，应扩展到对应跨模块
链路。阅读结束的标准是影响已经闭合，而不是打开过多少文件。
GCP 环境维护使用上面的专项路线，不要求先通读完整调度主线。普通 PR 不附加
线上验收、部署或重启，也不以“尚未在 GCP 验证”作为未完成理由。

## 从问题追到实现与断言

下面是入口指针，不替代 Owner 或 [Proof Registry](../../testing/proof-registry.md)。
按当前问题选一条；确认受影响的写入者、直接消费者和失败断言后即可停止扩展。
链接到测试表示断言存在，不表示本次改动已执行或通过该测试。

### 认证与用户目录

先读 [access-key front door](../../../server_jvm/README.md#access-key-front-door)和
[用户目录](../../../server_jvm/README.md#user-directory-and-access-keys)。请求从
[AccessKeyFilter](../../../server_jvm/src/main/java/com/xa/mass/server/api/AccessKeyFilter.java)
取得 caller；[UserDirectory](../../../server_jvm/src/main/java/com/xa/mass/server/user/UserDirectory.java)
负责目录与密钥发布。对照 [Filter 例外表断言](../../../server_jvm/src/test/java/com/xa/mass/server/api/AccessKeyFilterTest.java)
和[目录持久化、密钥替换、已准入 caller 与外部文件修改断言](../../../server_jvm/src/test/java/com/xa/mass/server/user/UserDirectoryTest.java)。
只改页面登录反馈时，闭合到 [Frontend 认证边界](../../../frontend/README.md#access-key-login-and-access)即可；
改变授权语义才继续追具体 Task/Scenario 操作。

### 有限任务保留与 Storage

先读 [finite retention](../../../server_jvm/README.md#finite-task-retention)和
[独立 history API](../../../server_jvm/README.md#finite-task-history-api)，沿
[TaskRetentionService](../../../server_jvm/src/main/java/com/xa/mass/server/task/retention/TaskRetentionService.java)
与 [TaskHistoryService](../../../server_jvm/src/main/java/com/xa/mass/server/task/history/TaskHistoryService.java)
定位维护和读取。现有[真实 Redis 保留断言](../../../server_jvm/src/test/java/com/xa/mass/server/task/retention/TaskRetentionServiceRedisIntegrationTest.java)
覆盖发布失败、释放及最终清理；[SQLite Store 断言](../../../server_jvm/src/test/java/com/xa/mass/server/task/history/TaskHistoryStoreTest.java)
覆盖分页前授权、scope 隔离、到期和完整导出。若改业务预览/CSV，继续到对应 Scenario Storage controller；
若只改入口或状态提示，到 [Frontend retention views](../../../frontend/README.md#finite-task-retention-views)及
[历史页面断言](../../../frontend/tests/task-history.test.ts)闭合。

### RPC 消费与回收

分别读 [消费和 idle TTL](../../../server_jvm/README.md#rpc-result-consumption-and-idle-reclamation)、
[可选终态 Item 保留](../../../server_jvm/README.md#rpc-terminal-item-retention)。消费等待从
[TaskRpcCallService](../../../server_jvm/src/main/java/com/xa/mass/server/task/call/TaskRpcCallService.java)进入，
维护从 [RpcTaskItemRetention](../../../server_jvm/src/main/java/com/xa/mass/server/task/retention/RpcTaskItemRetention.java)进入。
[消费 Redis 断言](../../../server_jvm/src/test/java/com/xa/mass/server/assembly/kernel/RpcResultConsumptionRedisIntegrationTest.java)
检查竞争 take、写刷新 TTL 及执行资源不变；[回收 Redis 断言](../../../server_jvm/src/test/java/com/xa/mass/server/task/retention/RpcTaskRetentionServiceRedisIntegrationTest.java)
检查默认关闭、两阶段清理及排除 SMS/有限任务。涉及响应丢失再追
[消费交接断言](../../../server_jvm/src/test/java/com/xa/mass/server/task/call/TaskRpcConsumptionHandoffTest.java)；
仅改说明时不扩展成有限历史迁移或 SMS 缓存改造。

### Worker 连接冷却

先读 [Server 冷却决策](../../../server_jvm/README.md#worker-connection-cooldown)，从
[WorkerConnectionControlService](../../../server_jvm/src/main/java/com/xa/mass/server/worker/connection/WorkerConnectionControlService.java)
追持久化后撤路由。[Service 断言](../../../server_jvm/src/test/java/com/xa/mass/server/worker/connection/WorkerConnectionControlServiceTest.java)
区分决策保存、撤路由失败和原请求重试；
[Adapter 连接断言](../../../transport/netty-adapter/src/test/java/com/xa/mass/workerdelivery/adapter/netty/internal/connection/WorkerConnectionMechanismTest.java)
检查活动/待验证连接撤销及迟到 allow。只有触及已准入 Handler/Reporter 或重连时，
才继续读对应 [Worker run](../../../transport/worker-core/README.md#one-worker-run)与
[Android callback/close](../../../transport/android-worker/README.md#callback-admission-and-close)合同。
连接冷却不替代 PAUSE 或 Matching 资格。

### 启动与交付

先读 [Boot profile 装配](../../../server_boot_jvm/README.md#platform-and-preview)及
[Distribution source launch](../../../distribution/server/README.md#source-launch)。代表实现是
[ServerBootConfiguration](../../../server_boot_jvm/src/main/java/com/xa/mass/serverboot/ServerBootConfiguration.java)、
[本地运行装配](../../../distribution/server/local_runtime.py)及[统一 archive verifier](../../../distribution/archive_tools.py)。
[Consumer 业务断言](../../../server_boot_jvm/src/test/java/com/xa/mass/serverboot/ConsumerBusinessIntegrationTest.java)
检查默认业务的外部配置、执行与重启；[launcher 断言](../../../distribution/server/src/test/python/test_run_preview.py)
和[archive 断言](../../../distribution/server/src/test/python/test_verify_preview_archive.py)检查源码/ZIP 边界及自有资源清理。
这些单元/集成入口不能代替提取包的实际启动；涉及交付 claim 时按
[runtime_distribution](../../testing/proof-registry.md#runtime_distribution)和
[product_coexistence](../../testing/proof-registry.md#product_coexistence)选现有 proof。

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
