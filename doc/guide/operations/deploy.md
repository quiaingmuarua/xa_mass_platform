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
| `consumer` | 外部 Host 接入 | 干净 Server/Adapter；资源和 Worker 显式提供 |
| `default` | 普通平台 | 无默认 Adapter、Group 或业务 Worker |

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

工作顺序是：**PR → CI → 发布不可变 Release → 明确部署 GCP → Preview 验收 → 问题反馈**。
发布成功不代表已经部署，部署成功也不代表现场验收完成。这里描述职责与操作顺序，
不代表仓库已有自动部署到 GCP 的流水线。

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
新 scope；GCP 不额外要求兼容层或历史迁移。版本编号和制品校验仍遵循既有发布规则。
某次升级可以复用 scope，只是该次升级的结论，不能推广为永久兼容义务。

切换前核对目标 Release、配置和数据处理方式，保留需要的旧制品及恢复点。同一 scope
只运行一个 Pacer；使用新 scope 不意味着可以顺带清空旧数据。失败恢复不自动清 Redis，
也不重新提交业务请求。正常运行期间仍执行 Owner 已有的数据保留与归档规则。

现场验收覆盖启动、公网入口、外部 Worker、任务结果及本次 Release 影响的功能，
不在 GCP 重复完整正确性、故障或性能 workload。分别记录版本与配置、实际操作、结果和
未覆盖的边界；浏览器操作未完成时保留待验收状态，不以 HTTP 成功替代。
证据区分见[验证指南](../verification.md#release-preview-的现场证据)。

### 新 Agent 从哪里开始

按 **README 定位 → 本节 → 只读核对主机记录 → 目标版本说明或对应 Owner** 阅读。
单纯维护环境无需先通读调度主线；需要修改代码时再按受影响 Owner 扩展。

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
| `/etc/xa-mass/OPERATIONS.txt` | 服务、配置、日志、凭据位置及操作路径 |

先核对这些记录，再按任务只读检查实际进程和配置；记录不是实时健康证明。
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
准备两个 HTTPS 入口：管理入口保护控制台和业务 API，Worker 入口只允许指定 Group 的
Prepare 与 WebSocket。两类 Basic 凭据不能串用；内部 Adapter API 和 Redis 不公开。
这是一套可信测试环境的门禁，管理凭据持有者仍可选择开发身份，Worker 凭据不提供
Worker ID 之间的隔离。它不承担客户 SLA。

先准备证书、Nginx 凭据文件和宿主 Group/Pool 配置，再从包内一次性渲染网关配置。
Server/Adapter 继续监听本机，公开 WSS 地址使用 Worker 域名。网页通过浏览器标准
Basic 认证进入；程序调用提供管理凭据及原有开发调用方头。

外部 Java Host 使用独立 Simulator `--config`，设置 HTTPS `runtimeApiBaseUrl` 和私有
`gatewayCredentialFile`。该路径相对主配置文件解析，读取失败不会回退匿名连接。
更换密钥需明确重启 Host。不要运行统一的本地环境启动器去连接已有远端 Server。
3.2 SDK 支持同样的传递，但该版本没有为 Android Demo 增加凭据配置页面。

删除凭据会拒绝新请求，立即撤销还需实际关闭已有网关连接，不能把 reload 当成已断连。
精确的认证、路由、限额及撤销规则以链接的网关示例为准。

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
