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
- **外部 Java Worker consumer**：使用明确固定版本的已发布
  [Simulator kit](../../../distribution/worker-simulator/README.md)。包内提供普通长期 20-Worker Host
  和 systemd 示例，以及现有 Performance runner 的
  [已部署 Runtime 模式](../../../integrations/worker-call-performance/README.md#deployed-runtime-consumer)。
  长期 Host 不运行任务定时器或健康判定；按需测量只启动自己的 Host/Harness，
  不启动或重配远端 Server/Redis。两种角色的 Group 和库存坐标应分开，
  同物理机仍共享资源和网络故障域。

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
其中 4.4.x Performance consumer 与 4.2.0 Loaded Recovery 是不同的 workload 和证据；
前者保留运行状态、unknown 与未修复问题，后者已按通过的 1,000 档结束。
复用已完成的证据，不从历史待办重新开始调查，也不把当前问题当作重跑完整压测的授权。

以下是当前环境的脱敏访问入口，不是应用协议常量；使用前与主机记录核对：

| 入口 | 地址 |
| --- | --- |
| 管理网页/API | `https://staging.xamass.net` |
| Worker Runtime | `https://workers-staging.xamass.net` |
| 授权后的 SSH 管理 | `root@136.85.101.232`，使用已有私有 SSH 凭据 |
| Ali 外部 consumer | 坐标来自 `release-preview-load` Environment 的 `CONSUMER_SSH_HOST`；campaign 使用 `CONSUMER_SSH_USER=xa-mass-performance`，主机管理使用已有私有运维入口 |

| 主机文件 | 查询内容 |
| --- | --- |
| `/etc/xa-mass/release.json` | 已安装版本、完整提交、来源和制品指纹 |
| `/etc/xa-mass/deployment.json` | 部署坐标、配置指纹、已完成/待补验收及证据范围 |
| `/etc/xa-mass/OPERATIONS.txt` | 服务、配置、日志、凭据位置、操作约定及操作路径 |
| `/etc/xa-mass/operations.log` | 每次主机改动的开始与结束，以及是否有未结束的操作 |
| Ali `/var/log/xa-mass-consumer/OPERATIONS.txt`、`operations.log` | consumer 安装、常驻服务、当前问题与主机操作记录 |
| Ali `/var/log/xa-mass-consumer/external-actions-setup/` | 私有安装／单次验收收据；不要复制原始身份或凭据到公开证据 |
| Ali `/var/lib/xa-mass-performance/runs/<RunID>/` | campaign 原始 helper、unit 收据和私有结果；可接回原 Run，不是重新提交 workload 的依据 |

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

### Consumer 版本同步与接回

Server 与 Consumer kit 分开固定版本：`INSTALLED_RELEASE_TAG/COMMIT` 描述 GCP Runtime，
`PERFORMANCE_KIT_RELEASE_TAG/COMMIT` 选择按需 campaign 消费的不可变 kit。
Ali 常驻 Host 的 `/opt/xa-mass-worker-simulator/current` 另有安装记录；它不跟随 Actions
自动切换。修改源码或发布 kit 不会自动更新这些坐标，当前值应从对应记录核对。

2026-11-19 已发布并部署 Runtime
`4.4.5/520b7ce314de24ad42ea079f97491919a24852b4`，复用原 active scope、SQLite、
账号／credits 和入口配置；外置 RPC 终态回收已按 1h／24h、每轮间隔 1h 启用。
`INSTALLED_RELEASE_*` 和 `PERFORMANCE_DEPLOYMENT_JSON.serverDeployment` 已同步到实际 Server，
配置指纹为 `sha256:8bf0a7ba13db534c8a1ee5b846c0e011ee35b84644103094dcd447755ae7cb2d`。
按需 Performance kit 继续为 4.4.4，常驻 Host 继续为 4.4.2，不由 Server 升级联动替换。
首轮维护 23,813ms 完成：删除 892,741 个输入、250,774 个 Item Score、69 个 Result；
现存 RPC 家族在新验收调用前分别由 892,741／1,279／892,741 降为 0／1,210／641,967。
Redis `used_memory` 由 545,717,368 降为 148,777,632 bytes；受保护的当前状态和旧 scope
保留的 347 个 key 未做清理，已核对保护项的指纹不变。外部有限任务 3 Item 和新 RPC 3 Item 均通过独立 UTF-8 MD5 核对，
Runtime、认证、对应版本文档与 SQLite 已核对。版本、制品、样本和边界见
[4.4.5 记录](../../../distribution/server/RELEASE-4.4.md#445-terminal-rpc-data-retention-patch)。
2026-11-19 09:58:31 UTC 已确认旧 4.4.0 制品、单份临时恢复点和临时 Runtime 下载均已删除；
GCP 升级与 Ali 独立恢复的操作日志均已关闭，主机标准记录已更新，GitHub Server pins 已回读。
独立的 4.4.4 Performance kit pins 保持不变。新 session 仍先检查当前主机记录和操作日志。

此次维护后，20 个常驻 Worker 没有自动恢复：Host 进程仍在，Lab 显示 desired RUNNING、
runtime STOPPED。通过原有本机 Lab 对每个原坐标显式 start 一次后，GCP 确认同 20 个身份
恢复；Host PID 53788、配置及 inventory 保持不变，没有进程重启或 SDK 重试预算调整。
以后计划内 Server 维护结束，先观察 Host 的实际 runtime 与 GCP 连接；若处于上述 STOPPED
状态，经既有授权主机通道调用本机
`POST /lab/v1/workers/{workerGroupId}/{labWorkerKey}:start`，再独立核对身份和连接。
这是本轮单次运维恢复，原始停止与恢复证据保留在主机记录。Lab 的可信同机边界与坐标归属见
[Simulator Owner](../../../worker_simulator_jvm/README.md#persistent-worker-lab)。

同日较早的 4.4.4 收尾后，按需 Performance kit pins 为
`4.4.4/01f795013ee9ded817304eba26e40805a21ddb89`；常驻 Host 保持 4.4.2，Runtime 保持 4.4.0。
GCP 的管理员连接另设 `admin_keepalive_requests=100000`，仍为 H2 8192 streams、
API 3000/s、burst 6000；该连接复用设置不改变请求速率、Worker 数量或性能判据。
最新 renderer Release、commit、ZIP SHA、工具目录和配置指纹见主机
`/etc/xa-mass/deployment.json` 的 `http2ReuseChange`；较早的 `http2TransportChange`
保留 4.4.3 的原始 invalid 观测。新的 10k Run44368693491 九项 passed，315,000 正式结果闭合，
自动清理及常驻 20 已独立确认，两台主机操作已关闭，原恢复点及临时 Runtime ZIP 已删除。
完整范围与非主张仍从[能力记录](../capabilities.md#external-public-closeout)进入。
这里是 dated 事实，下一次操作仍先核对原 Run、当前主机记录和未结束事项。

2026-11-19 的传输补丁将按需 Performance kit 更新为
`4.4.3/13e60437f30da282614a03c869a3e6871acdcfc7`，常驻 Host 保持 4.4.2，
Runtime 保持 4.4.0。GCP 入口单独消费发布 Runtime ZIP 中的 4.4.3 renderer，启用
HTTP/2 和显式 `http2_max_concurrent_streams=8192`，仅平滑 reload Nginx；
原 API 3000/s、burst 6000 及 Worker 限额不变。实际来源、工具／证据目录和配置指纹见
`/etc/xa-mass/deployment.json` 的 `http2TransportChange`，不以此将 Runtime 重标为 4.4.3。
以后修改该入口时，从这条记录取得 renderer Release／commit／SHA；校验发布 ZIP 后，
用对应 renderer 生成私有候选配置，按主机 Nginx 启动参数执行语法检查，再 reload。
旧 4.4.0 安装内的 renderer 不认识新增字段，不应使用它重写当前入口配置。
同步 `PERFORMANCE_DEPLOYMENT_JSON.serverDeployment.configurationId`，保留实际 Server
版本及 `INSTALLED_RELEASE_*`。本轮观测状态从[能力记录](../capabilities.md#external-public-closeout)
进入；版本同步本身不证明测量或清理成功。

2026-11-18 已将 Ali 常驻 Host 更新为 4.4.2，并将按需 Performance kit pins 设为
`4.4.2/ccf676801663f953169f2d03058cbe7236a01123`。安装核对保留了原 20 个 Worker
身份和配置／凭据／库存指纹；GCP Runtime 仍为
`4.4.0/b28a87ac918a34ffabb4feb8c63a29998a0610db`，没有替换 Runtime 或改动其配置。
新 10k campaign 44297177821 已完成，原身份的自动连接 cleanup 已 confirmed；
invalid/saturated case 和 560 个 unknown 仍按原回执保留。75 分钟空闲观察及一次
受控出站 DROP／恢复验收也已完成：无进程重启、原 20 个逻辑身份和输入保留；
恢复后本地 TCP 重建与 GCP 确认分别计时，后者为 59.619 秒。
这不表示零瞬时断线或实时健康，详见
[4.4 Release 说明](../../../distribution/server/RELEASE-4.4.md)。这些是 dated 安装事实，
后续任务仍须核对主机记录，不重新生成库存来掩盖旧状态。
本轮 Ali 与 GCP 的既有运维记录已更新，两个操作日志均已关闭本次操作；
没有 Runtime／Nginx restart 或 reload，GCP 的原 Runtime 制品和受保护配置保持不变。

新 session 先读两台主机的操作日志及原 GitHub Run ID。仍在运行时接回同一个 Run／unit；
使用 [CI 等待规则](../../../TESTING.md#long-running-commands-and-ci-waiting)保持一个 waiter。
同 Run ID rerun 只观察／收取原收据，不重新执行 campaign。已有未知结果和原始失败收据
继续保留；进程仍活跃或 cleanup 未确认时先核对，不能先删除目录或重放请求。
当前环境角色以仓库本节和主机外置说明为准，运行版本仍以实际安装及记录核对。

### RPC 终态数据保留

发布默认保持 RPC 终态回收关闭。2026-11-19 已在 GCP 的 4.4.5 Runtime 外部配置中明确
启用并完成首轮验收；机制版本、实际配置和清理结果见前述记录。后续部署仍须按目标版本
说明选择，不因源码或 kit 更新而自动开启。在既有 `xa.mass.task-retention` 下设置：

```yaml
rpc-item-retention-enabled: true
rpc-input-retention: 1h
rpc-terminal-retention: 24h
```

既有 retention 总开关也必须开启。规则只面向有明确 raw 归属的 parked RPC Task：
按终态 Score 内时间坐标，1h 后输入可回收，24h 后 Result、Score 和残留输入可回收。
ACTIVE Item、有限任务、SMS、Worker 注册与旧 scope 不在该清理范围内。
执行复用现有维护器，受全轮预算和空闲机会影响，并非到点立即删除。

启用前明确结果核对和去重窗口：旧调用过期后不自动重放，新业务调用用新 messageId；
未消费 Result 也会在符合 24h 条件后清除。历史输入可读为 null，Item 总数表示保留数量。
启用后观察聚合扫描／删除／跳过／错误计数与 Redis 内存；不以 key 数量或 RSS 必须归零验收。
关闭开关只停止后续删除，不能恢复已清数据。完整规则由
[Server Owner](../../../server_jvm/README.md#rpc-terminal-item-retention)维护。

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
通过现有 Performance workflow 手工调度外部调用测量时，使用
[External Actions 入口](../../../integrations/worker-call-performance/README.md#external-actions-execution)；
它消费固定版本的已发布 Release kit，并保持长期 20-Worker 系统服务独立。
Consumer kit 的 Release/commit 与目标 Server 的部署记录分别固定、核验和追溯；
仅更新 kit 不会升级 GCP，Properties 就绪核验沿用同一 Harness 并保留历史 Group 注册。
不 checkout 仓库时，使用所选 Release 发布的
[Worker Simulator kit](../../../distribution/worker-simulator/README.md)：它包含这个 Host 入口和
一轮有限任务验收命令，后者输出一行 JSON（passed / failed / unconfirmed）。

长期普通 Worker 只需 Host、Worker 凭据及独立 inventory，由 systemd 维持进程，
正常接任务并按 SDK 机制重连。常驻 20 Worker 不持有 operator 密钥，也不负责定时提交
验收任务或判断健康状态；其 Group 与 Performance 测量 Group 分开。

需要小规模手工检查时，独立消费者可以使用自己的 operator 用户与 Worker 凭据，
调用 kit 的 `finite_round.py` 并保留该轮 JSON；这是可选的一轮验收，不是常驻服务职责。
发现问题可提交 GitHub issue（Preview finding 表单），附上脱敏结果。

### 外网 CI 入口与历史容量验收

当前外网按需 CI 只使用前述 Performance 入口，消费固定 Release kit；
Loaded Recovery 的公网 Actions 路线已于 2026-11-19 退役。本地 Loaded
Nightly／手动流程继续负责原恢复证明，不访问 GCP。

旧 Loaded 公网 campaign 的基础档通过、完整 15k → 10k 未通过和原始失败，
继续保存在[能力收尾记录](../capabilities.md#external-public-closeout)。这些是历史
结论，不是继续运行旧负载的指令；新 Performance 结果也不覆盖它们。

schema-3 kit 仍包含 `acceptance/run_loaded.py`，供现有本地 ZIP／TLS 交付检查
消费，详见[成品消费契约](../../../integrations/worker-loaded-recovery/README.md#external-release-capacity)。
它不再提供第二条公网 CI 入口。共享 `release-preview-load` Environment、
Performance 的 kit／Server pins 和常驻 Worker 的账号、inventory、凭据仍按各自职责维护。

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

运行目录、Worker inventory、Batch Checks 配置和 Task history 的保留不是同一件事。
部署需保留配置与 SQLite 历史所在的实际路径；目录选择和任务保留规则见
[Distribution history](../../../distribution/server/README.md#finite-task-history)。
只有明确拥有的临时 `test_*` scope 才可按仓库规定清理；不用全库删除来恢复服务。

运行异常从[排障](troubleshooting.md)定位，不通过改调度参数、清空数据或更换请求 ID
掩盖未确认操作。
