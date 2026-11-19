# 配置放在哪里

Status: current configuration ownership guide.

配置同一业务时可能涉及多份文件，因为它们控制不同的事实。先确定要改变的是
进程坐标、资源、Worker 能力，还是业务解释；不要把这些都塞进一个场景 JSON。

## 配置归属表

| 要改变什么 | 维护入口 | 生效与保留 |
| --- | --- | --- |
| HTTP/Adapter 端口、Redis、profile、进程工作目录 | [本地启动器](../../../distribution/server/README.md#source-launch)及 [Boot](../../../server_boot_jvm/README.md#pages-and-configuration) | 启动时解析；所有子进程使用同一份最终坐标 |
| Project 与 Group 关系、Matching Pool/query、窗口与反馈绑定 | [Boot 宿主配置](../../../server_boot_jvm/README.md#project-topology)及 [Matching Owner](../../../worker_matching_jvm/README.md#fixed-resource-composition) | 启动声明；Project 授权不会启用资源 |
| Group catalog 中的事件声明 | [Server Group 准备](../../../server_jvm/README.md#workergroup-and-worker-preparation) | catalog 是声明；不能替代已安装 Handler |
| Java Worker 的数量、事件、属性、inventory | [Simulator JSON](../../../worker_simulator_jvm/README.md#one-configuration-one-entry) | Host 启动装配；数量调整保留已有身份坐标 |
| Android Worker 能力与应用生命周期 | [Android Host](../../../xa-android/README.md) | 由独立应用及 SDK 装配 |
| Batch Checks 的检查项、参数、单价、执行绑定 | [场景配置文件/页面](../../../scenarios/app-checks-jvm/README.md#模块内配置存储) | 文件保存；重启生效；旧 Task 保留首次执行快照 |
| Messages 应用到 Group 的选择 | [Messages applications](../../../scenarios/message-campaigns-jvm/README.md#worker-供给与装配) | 新建 Task 解析；旧 Task 不随映射改写 |
| 用户、角色、Project 授权与访问密钥 | [Server 用户目录](../../../server_jvm/README.md#user-directory-and-access-keys) | `xa.mass.users.seed` 指向的文件；运行中只由 Server 写入，手工修改需先停止 Server；consumer 必须显式配置 |
| 用户积分 | [Server 账户](../../../server_jvm/README.md#user-credit-accounts) | Server 自有 Redis 账本；与用户目录分开 |
| Task 历史数据库路径和保留时间 | [Server retention](../../../server_jvm/README.md#finite-task-retention) | 配置由 Boot 提供；Runtime 与历史分别到期，保存与清理由 Server/Kernel 分工完成 |
| RPC Result 空闲过期 | [Boot RPC Result 配置](../../../server_boot_jvm/README.md#rpc-result-and-sms-cache-configuration) | 实际 RPC Result 写入刷新整个 HASH 的 TTL；不回收 Item 或 Task |
| RPC 终态 Item 回收 | [Boot 可选回收配置](../../../server_boot_jvm/README.md#rpc-terminal-item-retention-configuration) | 默认关闭；与父级 retention 一同启用后才维护，不因安装或升级而启用 |

consumer 默认启用 Batch Checks 和 Messages，但不自动提供应用、检查项、Group 或 Pool。
使用[两业务组合样例](../../../distribution/server/src/main/dist/examples/consumer-business/README.md)准备有效外部配置。
只用一个业务时关闭另一个；纯平台 consumer 显式将两项 `enabled` 设为 `false`。
缺配置时启动失败，不会自动套用 Preview 演示数据。SMS 继续仅由 Preview 装配。

## 用户密钥与 Worker 凭据

Console 登录和 API 请求使用用户 access key；角色、Project 授权及 Task 所有者决定具体操作权限。
consumer 的 `xa.mass.users.seed` 必须显式指定。需要在页面维护用户时使用外部 `file:` 目录；
classpath seed 只读。本地开发公开密钥只用于本地示例，外部部署按
[用户目录合同](../../../server_jvm/README.md#user-directory-and-access-keys)在 Server 停止时准备首个管理员。

运行中通过 Server 管理用户、授权及密钥，变更作用于之后准入的请求；已创建 Task 的归属不变。
签发的新密钥只显示一次，替换或清除后旧密钥不能用于新请求。手工修改目录文件前先停止 Server，
不要绕过它的文件锁和完整文档发布。

Worker 的网关凭据配置属于 [staging ingress 与 SDK 连接选项](../../../distribution/server/src/main/dist/examples/staging-ingress/README.md)，
不使用 Console 的用户密钥，也不将网关凭据当作 Worker 身份。管理员在页面设置的
[连接冷却](../../../server_jvm/README.md#worker-connection-cooldown)是当前 Worker 的动态准入决策，
不修改 Group/Pool 配置；到期或解除后还需独立观察连接及调度状态。

## 数据保留与文件位置

有限 Task 的 Runtime 数据和 Storage 历史使用不同读取入口；用户操作见
[Runtime 与 Storage](../usage/workbench.md#有限任务的-runtime-与-storage)。历史库路径由
[Boot retention 配置](../../../server_boot_jvm/README.md#finite-task-retention-configuration)提供，
应放在可写、可随 Runtime 重启保留的位置，避免放入不可变安装目录。
Runtime 与历史到期都从首次终止计时，读取不会延期；页面展示当前任务的实际时间。

历史库使用 SQLite，备份、旧数据边界和 WAL 处理见
[Server history API](../../../server_jvm/README.md#finite-task-history-api)。旧 JSONL/manifest 目录不会
自动转成新历史库，改路径或 scope 也不会迁移记录。保留策略不负责删除用户或积分账本。

RPC 的消费、Result idle TTL 和可选终态 Item 回收要分别配置和判断；见
[RPC 结果读取与回收](../usage/workbench.md#rpc-结果读取与回收)。其中只有终态 Item 回收需要显式启用，
它不提供 Storage 历史。不要用有限 Task 的历史保留期推算 RPC 可重读或去重的期限。

## 新增一个 Batch Checks 检查项

同一个结果协议可以复用现有静态契约；新协议需要先实现契约代码。一个可执行检查项
至少需要同时具备：

1. Worker Host 安装对应完整事件名的 Handler。
2. 宿主注册 Group，并声明事件及所需 Pool/query；窗口资源同时具备配套反馈。
3. Batch Checks 配置绑定检查类型、Group、event、refill、selector、参数与正整数单价。
4. Server 启动依赖校验通过，Worker 连接且满足该查询资格。

管理员保存配置只校验文档自身，不能证明依赖已经准备好。保存后手动重启，
再检查 Catalog 和实际执行；用一小批明确答案完成闭环。

## 配置已保存，为什么行为没变

先比较配置页面的“已保存”和“当前生效”文档。Batch Checks 的运行对象只在启动时创建，
保存不会热更新。seed 只初始化不存在的配置文件；升级包中的 seed 不覆盖已经保存的文件。

再检查观察的是新 Task 还是旧 Task。旧 Task 保存了检查类型、执行绑定、参数、分组大小
和单价，后续导入与结果解释继续使用旧快照。不能通过编辑当前配置改变已创建任务。

改变窗口长度或进行破坏性资源切换时，按 Owner 使用新 scope 验证。不要自动清理旧
环境或修改在运行实例的锁定配置文件；正常停止后再做明确的离线文件操作。

## 提交前记录什么

记录实际使用的 profile、CLI、配置路径、Redis scope、工作目录和代码/包版本。
日志中的启动坐标和配置指纹可帮助区分环境；不要在工单或共享日志粘贴密码、
完整 Worker payload 或业务结果。
