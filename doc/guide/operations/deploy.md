# 部署与停止

Status: current deployment navigation and operating guide.

先区分本地演示启动与 [GCP Release Preview](#release-preview) 的版本部署。
Release Preview 是环境用途，不是 profile 名称；它使用已发布 Runtime 和外部 Worker，
不要求使用本地 `preview` profile 或包含 Simulator 的 Preview ZIP。
Server 与 Worker Host 是独立进程；Redis 由环境提供，Server 不会替你启动 Worker。

## 从源码启动

> **默认 Preview 是临时环境：正常退出会删除该 scope 的任务、结果、积分等 Redis 数据及本次私有任务归档。**
> 需要保留这些数据时，从首次启动起使用下面的固定 scope 命令；仅指定 `--work-dir` 不会关闭临时 scope 清理。

第一次体验用[快速启动](../quick-start.md)。需要跨重启保留同一套本地数据时，
明确设置工作目录和 Redis scope：

```powershell
python run_local_runtime.py --profile preview --work-dir .runtime/guide-preview --redis-scope guide_preview
```

该示例 scope 会保留，正常退出不会自动删除它。再次启动复用同一个目录和 scope；
Task 自身的归档和到期清理策略仍然生效，固定 scope 不表示永久保留。
不要让另一 Server 同时在该 scope 启用 Pacer。更换配置前确认是否会影响保存的 Task、
Group 绑定和窗口状态；不兼容切换使用新的环境坐标。

| profile | 当前用途 | 默认行为 |
| --- | --- | --- |
| `preview` | 三个业务场景的本地体验 | Server、Adapter 与 Java Simulator |
| `consumer` | Batch Checks／Messages 与外部 Host 接入 | 两业务默认启用，必须提供有效业务及资源配置；不自动启动 Worker，SMS 不装配 |
| `default` | 普通平台 | 无默认 Adapter、Group 或业务 Worker |

consumer 的完整外部输入见[两业务组合样例](../../../distribution/server/src/main/dist/examples/consumer-business/README.md)。
旧的纯平台 consumer 在升级时需显式将 `xa.mass.scenarios.app-checks.enabled` 和
`xa.mass.scenarios.messages.enabled` 设为 `false`；只用一个业务时关闭另一个。
这项默认装配变化不自动更新已运行的 GCP，仍由下一次明确的 Release 部署应用。

具体默认端口和所有参数属于
[Distribution source launch](../../../distribution/server/README.md#source-launch)。
启动输出中的最终坐标优先于旧终端记录或另一个环境的地址。

## 从发行包启动

Runtime ZIP 包含 Server、Adapter 和前端，排除 Simulator；Preview ZIP 另外包含
独立 Java Host 和有限启动器。两者都包含当前文档站的静态页面，访问同一站点 `/docs/`。

- **Runtime ZIP**：按包内 manifest 指定的 JAR 和 profile 启动，提供外部 Redis、
  静态目录及宿主配置。完整命令见
  [Runtime archive](../../../distribution/server/README.md#runtime-archive)。
- **Preview ZIP**：在新解压目录安装包内 requirements，再运行 `python run_preview.py`。
  默认仍为退出清理数据的临时环境；需保留时传入 `--redis-scope <固定名称>` 并复用同一 `--work-dir`。
  包内不使用源码 `--build`，也不回退到其他 checkout。见
  [Preview archive](../../../distribution/server/PREVIEW.md#archive-delivery)。
- **独立 Batch Checks**：使用宿主 YAML、Group JSON、场景配置 JSON 三份输入，
  见[包内部署示例](../../../distribution/server/src/main/dist/examples/batch-checks/README.md)。
- **独立 Messages/Android**：使用[消息示例](../../../distribution/server/src/main/dist/examples/messages/README.md)
  和 [Android Host](../../../xa-android/README.md)。

新构建的候选 ZIP 不会自动替换已经运行的安装。更新时先核对版本、commit 和实际文件，
在独立目录验证，再明确切换。不要修改运行中的包或用旧文档推定数据兼容。

## Release Preview

**GCP 是按 Release 推进的长期 Preview 环境。GitHub CI 负责构建和自动化证明；
每个 Release 在 GCP 完成真实部署与小规模使用验证，普通 PR 不默认更新 GCP。**
它不承担客户生产 SLA。长期存在的是环境，不要求接口、配置或数据永久兼容。

两个任务的完成范围分别是：

- **日常开发：代码与 Owner → 本地针对性验证 → 受影响 CI → PR 交付。**
- **明确的版本部署：选择已发布的不可变 Release → 部署 GCP → 小规模验收 → 问题反馈。**

日常开发和项目理解默认不连接 GCP、不读取主机记录或取用其凭据。Android Worker、
API 认证和网关能力都可以在本地 Server／Adapter、设备、测试网关及现有 CI 中开发验证。
没有 GCP 验收不影响普通代码任务完成，也不要求仓库接口始终兼容当前已部署版本。
只有任务明确包含版本部署、现场诊断或公网验收时，才进入环境流程；已有发布 Proof
要求和该任务约定的验收仍需完成。发布成功不代表已经部署，部署成功也不代表现场验收完成。

| 部分 | 职责 |
| --- | --- |
| GitHub CI | 构建、自动化证明和可追溯制品；完整发布证明遵循既有发布规则 |
| GCP | 消费已发布制品，持续运行 Runtime/Adapter、Redis 和公网入口，执行小规模部署验收 |
| 外部 Java/Android Host | 从开发机、设备或其他主机连接，提供实际或模拟的 Worker 能力 |
| Agent | 在任务授权范围内部署、诊断、验收及整理证据；不充当 Watchdog |
| systemd/ACME | 负责已有服务的进程恢复与证书续期，不依赖 Agent 持续运行 |

GCP 不构建源码、不运行完整 CI，不安装或启动 Worker Host。环境内没有在线 Worker 时，
不能从 Server 就绪推断任务可执行。使用的 Group、Project、Pool 和场景仍由
[各自配置 Owner](configuration.md) 决定，Release Preview 不自动启用这些资源。

### 版本切换与验证边界

允许破坏性接口、配置和数据变化。目标版本说明决定复用已有数据，还是显式采用新配置、
新 scope，并写明 Preview 需要的配置调整和验收重点；这些判断随 PR 审查，主机上只执行，
不临场设计。GCP 不额外要求兼容层或历史迁移。版本编号和制品校验仍遵循既有发布规则。
某次升级可以复用 scope，只是该次升级的结论，不能推广为永久兼容义务。

切换前核对目标 Release、配置和数据处理方式。GitHub 上的不可变 Release 是唯一的制品归档，
主机只保留正在运行的版本；需要旧版本时重新下载并校验。每次升级只做一份临时恢复点
（主机配置、用户文件、入口配置、Redis 快照和 archive），验收通过后连同上一版本目录一起删除，
之后的问题通过新 Release 修复。本地恢复点不是异地灾备。同一 scope 只运行一个 Pacer；
使用新 scope 不意味着可以顺带清空旧数据。失败恢复不自动清 Redis，也不重新提交业务请求。
正常运行期间仍执行 Owner 已有的数据保留与归档规则。

GCP 只承担固定版本的长期验收：日常 PR 与问题修复在本地和 CI 证明，随下一个 Release
进入 GCP。每次现场验收都包含基线：就绪、公网入口认证、一次经外部 Worker 的有限任务及
结果核对；其余只覆盖本次 Release 改变的功能，Console 行为变化时才执行浏览器流程。
不在 GCP 重复完整正确性、故障或性能 workload。分别记录版本与配置、实际操作、结果和
未覆盖的边界；浏览器操作未完成时保留待验收状态，不以 HTTP 成功替代。
若某个 Release 明确安排外部容量验收，按该版本说明执行独立 campaign，并记录其规模与
边界；这不会把日常 PR 或每次部署的默认小规模检查升级成负载实验。
证据区分见[验证指南](../verification.md#release-preview-的现场证据)。

### 新 Agent 从哪里开始

以下读取路线用于环境任务；普通项目理解使用仓库 README、Owner、代码和测试即可。
按 **README 定位 → 本节 → 只读核对主机记录 → 目标版本说明或对应 Owner** 阅读。
单纯维护环境无需先通读调度主线；需要修改代码时再按受影响 Owner 扩展。

涉及外部连接/容量时，先读[公网验收收尾](../capabilities.md#external-public-closeout)。
该轮已按通过的 1,000 档结束；100 档仅保留历史证据。完整规模未通过不表示仍有待执行的压测任务。
复用已完成的端口、跨系统、物理出口和 CI 对照证据，不从历史的待办文字重新开始调查。

以下是当前环境的脱敏访问入口，不是应用协议常量；使用前与主机记录核对：

| 入口 | 地址 |
| --- | --- |
| 管理网页/API | `https://staging.xamass.net` |
| Worker Runtime | `https://workers-staging.xamass.net` |
| 授权后的 SSH 管理 | `root@136.85.101.232`，使用已有私有 SSH 凭据 |

| 主机文件 | 查询内容 |
| --- | --- |
| `/etc/xa-mass/release.json` | 已安装版本、完整提交、来源和制品指纹 |
| `/etc/xa-mass/deployment.json` | 部署坐标、配置指纹、已完成/待补验收及证据范围 |
| `/etc/xa-mass/OPERATIONS.txt` | 服务、配置、日志、凭据位置、操作约定及操作路径 |
| `/etc/xa-mass/operations.log` | 每次主机改动的开始与结束，以及是否有未结束的操作 |

先核对这些记录，再按任务只读检查实际进程和配置；记录不是实时健康证明。

主机改动只固定几条约定，其余由执行的 Agent 判断，精确格式见 `OPERATIONS.txt`：

- 只读诊断无需记录；改动需用户对该改动授权，开始前和结束后各在操作日志写一行。
- 同一时间只有一个操作者：日志里有未结束的操作时先停下询问用户。
- 版本特有步骤只来自目标版本说明；说明未写到的数据、凭据或 scope 处理先询问。
- 备份、验收用户和临时 Worker 凭据使用固定名称。
- 记录改写为当前状态，历史只在操作日志留一行。
例如 API/Worker 已通过与 `browserFlow` 待验收可以同时成立。Android SDK 通过也不代表
Android App 已在公网使用，未执行的外部环境连接不能从本地结果推定。

本地被 Git 忽略的 `build/deployments` 只是副本，不能成为全新 checkout 的唯一交接入口。
仓库只维护职责、步骤和记录位置，不保存凭据值或完整线上状态镜像。读取运维说明不等于
需要读取或输出其中指向的秘密文件。

GCP 的 `/docs` 随已安装 Runtime 制品发布，描述的是该制品的源版本。仅更新仓库与外置
运维说明不会更新它，也不应覆盖解压后的网页/JAR；网页文档随下一次正常 Release 更新。
当前环境角色以仓库本节和主机外置说明为准，运行版本仍以实际安装及记录核对。

### 公网接入

使用 Runtime ZIP 的 [staging ingress 示例](../../../distribution/server/src/main/dist/examples/staging-ingress/README.md)
准备两个 HTTPS 入口：管理入口承载控制台和业务 API，Worker 入口只允许指定 Group 的
Prepare 与 WebSocket。管理入口没有网关凭据，只把格式正确的 `Bearer` 访问密钥转给 Runtime，
由 Runtime 的[访问密钥统一入口](../../../server_jvm/README.md#access-key-front-door)认证；
Worker 入口使用共享 Basic 凭据，不提供 Worker ID 之间的隔离。内部 Adapter API 和 Redis 不公开。
它不承担客户 SLA。

先准备证书、Worker 凭据文件、用户文件和宿主 Group/Pool 配置，再从包内一次性渲染网关配置。
Server/Adapter 继续监听本机，公开 WSS 地址使用 Worker 域名。网页在登录页输入访问密钥；
程序调用提供 `Authorization: Bearer <access key>`。

`consumer` profile 必须用 `xa.mass.users.seed` 指向自己的用户文件，例如
`file:/var/lib/xa-mass/users.json`；文件所在目录需要 Runtime 服务账户可写，运行中只由
Server 写入。全新环境的第一个管理员密钥在 Server 停止时写入：

```sh
key="xak_$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')"
printf '%s' "$key" | sha256sum   # 把这个十六进制值写入管理员的 accessKeySha256
```

明文密钥只私下交给管理员，不写入命令历史、日志或仓库；需要持久保存时，仅存入受限的私有凭据文件。
之后的用户、角色、
Project 与密钥签发／清除都在控制台或 API 完成；Server 运行时手工改文件会在下次写入时
返回 409，重启后才生效。内置开发目录的公开密钥 `xak_dev_*` 只用于本地，不能用于外部环境。

外部 Java Host 使用独立 Simulator `--config`，设置 HTTPS `runtimeApiBaseUrl` 和私有
`gatewayCredentialFile`。该路径相对主配置文件解析，读取失败不会回退匿名连接。
更换密钥需明确重启 Host。不要运行统一的本地环境启动器去连接已有远端 Server。
不 checkout 仓库时，使用同一 Release 发布的
[Worker Simulator kit](../../../distribution/worker-simulator/README.md)：它包含这个 Host 入口和
一轮有限任务验收命令，后者输出一行 JSON（passed / failed / unconfirmed）。

任何机器都可以作为长期运行的外部使用者（soak）：它只下载 kit，用自己的 operator 用户、
Worker 凭据和独立 Group，按自己的节奏低频跑验收轮次，不参与编码；发现问题时提交 GitHub
issue（Preview finding 表单），附上该轮 JSON。仓库和主机都不为它保存调度器，它的 Group 与
验收 Group 分开，避免执行验收任务。

### 显式外部容量验收

需要衡量已发布 Runtime 的外部负载时，使用同 Release 的 Simulator kit 中
`acceptance/run_loaded.py`。它在外部 Linux 主机启动自己的 Java Host 和编译好的
Harness，不在 GCP 构建源码或运行 Worker Host，也不注入 Server 重启／故障。
发布工具不带规模覆盖时仍为完整 15,000 Worker、保留 10,000 Worker，
各运行十个 Task、每 Task 5,000 Item；分别记录两阶段结果。
当前手动 CI 的 `external_scale` 默认选择已验的 1,000 → 999 基础档，
显式使用 `--diagnostic` 并保留 `diagnostic-only` 结果口径；100 档已移除。
原 15k → 10k 模式仅通过 `external_scale=15000` 显式选择；其原门槛没有降低，
本轮也没有通过它。不要把默认小档的通过报告成完整容量通过。

这个入口使用明确的管理 API／Worker HTTPS 地址、Project、Group、Endpoint Manager、
operator 访问密钥文件、Worker 凭据文件和输出目录。它没有 SSH、管理员密钥或 Redis
清理权限。Group 的 inventory 坐标必须由一个 Host 独占，另一目录不会隔离相同身份。
原来的 `finite_round.py` 仍只处理每轮 1～100 Item，低频 soak 与本次容量 campaign 分开。

既有 loaded-recovery workflow 默认及定时运行仍为 `local-proof`，保留原本地故障与断言；
`external-release` 只能从 `main` 手工触发，使用受保护的 `release-preview-load` Environment。
其坐标、密钥与制品核验见 [TESTING](../../../TESTING.md#external-release-load)，
完整命令与结果口径见[外部消费契约](../../../integrations/worker-loaded-recovery/README.md#external-release-capacity)。

初始预算、主机配置及运维记录按 [4.2 Release 说明](../../../distribution/server/RELEASE-4.2.md)
处理；消费者自身的进程采样不能替代 GCP 的 Server／Redis／Nginx 资源观测。结果速率包含
公网、Worker 执行和观测延迟，不代表 Server 极限 TPS 或全部在线 Worker 都执行过任务。
失败和未确认保留原回执，不重提任务、不调小规模掩盖失败。完成后停止本次消费者进程并
删除临时凭据副本，长期配置、业务数据和正常归档仍按环境约定保留。这里说明执行方法，
不宣称当前 GCP 已通过该规模验收，也不增加普通开发任务的 GCP 依赖。

### Android 公网连接

Android SDK 支持同样的凭据传递；当前 App 使用
[静态构建、安装和私有凭据注入](../../../xa-android/README.md#public-runtime-connection)，
不通过配置页面更改连接。公网数据链路不依赖 SSH 隧道或 ADB reverse。

删除 Worker 凭据会拒绝新请求，立即撤销还需实际关闭已有网关连接，不能把 reload 当成已断连。
清除用户的访问密钥后，该密钥的下一次请求即被拒绝。精确的认证、路由、限额及撤销规则以
链接的网关示例和 Server 用户目录为准。

## Java 与 Android Worker

Java Host 通过一个 JSON 选择能力、属性、身份 inventory 和数量。Android 使用既有
设备上的独立 Lab APK，启动参数可选择数量和设备；启动器不会新建 emulator。
数量是 Worker 实例数，不是线程数或设备数。

选择 Worker 只控制进程与实例，不会替你注册 Group、启用 Pool 或安装声明中没有的 Handler。
外部部署先准备[配置归属](configuration.md)中的资源，再确认 Worker 实际安装能力。

## 停止与保留

优先对本次启动器使用 Ctrl+C，等待其关闭拥有的 Worker 和 Server、恢复触碰过的 ADB 映射。
不要按进程名称批量结束其他 Java/ADB 进程。异常退出后，依据本次启动目录记录的
PID、端口和 scope 检查残留。

运行目录、Worker inventory、Batch Checks 配置和 Task archive 的保留不是同一件事。
部署需保留配置与 archive 所在的实际路径；目录选择和任务保留规则见
[Distribution archives](../../../distribution/server/README.md#finite-task-archives)。
只有明确拥有的临时 `test_*` scope 才可按仓库规定清理；不用全库删除来恢复服务。

运行异常从[排障](troubleshooting.md)定位，不通过改调度参数、清空数据或更换请求 ID
掩盖未确认操作。
