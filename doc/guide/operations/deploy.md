# 部署与停止

Status: current deployment navigation and operating guide.

先确定部署的是业务 Preview，还是由自己提供资源与 Worker 的 consumer。
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

## 长期 Staging 公网接入

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
Android SDK 支持同样的传递，但本片没有为 Android Demo 增加凭据配置页面。

GCP 只下载固定 Release；构建和完整证明留在开发环境/CI。升级前保存恢复点，保持同一
scope 只有一个 Pacer。发布后仅做小规模任务、结果和导出验收。删除凭据会拒绝新请求，
立即撤销还需实际关闭已有网关连接，不能把 reload 当成已断连。进程与证书续期由
systemd/ACME 工具管理，不依赖 Agent 持续运行。

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
