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
| 开发用户、角色与 Project 授权 | [Server 用户目录](../../../frontend/README.md#development-login-and-access) | 初始 JSON + 进程内管理；重启重新加载 seed |
| 用户积分 | [Server 账户](../../../server_jvm/README.md#user-credit-accounts) | Server 自有 Redis 账本；与用户目录分开 |
| Task 归档路径和保留时间 | [Server retention](../../../server_jvm/README.md#finite-task-retention) | 配置由 Boot 提供，归档与清理由 Server/Kernel 分工完成 |

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
