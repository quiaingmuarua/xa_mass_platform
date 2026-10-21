# 快速启动

Status: current source and packaged entry guide.

第一次体验使用 Preview：它启动一个 Server/Adapter 进程和独立 Java Worker Host，
同时启用三个业务场景。先运行真实环境，再做[第一个 MD5 检查任务](first-task.md)。

## 准备环境

在仓库根目录执行命令。源码构建需要 Java 21、Python 3.11+、Node 22.19 至 24.x，
以及 Corepack 或 pnpm 11.9.0。Gradle 使用仓库 wrapper。准备可访问的 Redis 7；
启动器不会安装、启动或停止 Redis。完整要求由
[Preview delivery](../../distribution/server/PREVIEW.md#source-launch)维护。

> **数据保留提醒：下面的默认 Preview 命令启动临时环境。正常退出会删除该 scope 的任务、结果、积分等 Redis 数据，以及本次私有任务归档。**
> 配置文件和 Worker inventory 会保留，它们不包含上述运行数据。
> 需要跨重启保留数据，请在启动前使用[部署指南中的固定 scope 命令](operations/deploy.md#从源码启动)，并持续复用同一工作目录。

```powershell
python -m pip install -r distribution/server/requirements-preview.txt
python run_local_runtime.py --profile preview
```

如果 Redis 不在默认的 `127.0.0.1:6379/15`，显式传入 `--redis-url`。
启动器会构建当前代码、打印最终进程坐标，并在就绪后输出 `Ready` 与日志目录。
先解决终端报告的启动错误，不要对已有端口上的其他服务强制关停。

## 打开页面

默认 Console 是 [http://127.0.0.1:18500](http://127.0.0.1:18500)，
设备模拟控制台是 [http://127.0.0.1:18504/lab](http://127.0.0.1:18504/lab)。
修改端口时以启动输出为准。

1. 在 Console 选择开发身份。默认目录包含管理员 `1000`、用户 A `1001` 和用户 B `1002`。
2. 管理员可以观察 Runtime、管理用户和积分；普通用户按 Project 授权管理自己的业务任务。
3. 打开“批量检查”，确认可以读取检查能力，然后按[第一个任务](first-task.md)执行。

这里的开发身份没有密码或真实 session 认证。部署访问控制属于实际接入环境，
当前角色和 Project 检查不能代替用户认证。说明见
[Frontend 登录与访问](../../frontend/README.md#development-login-and-access)。

## 首次启动和再次启动

Preview 默认启动 60 个 Demo Worker、App A/B 各 20 个 Worker；配置和完整清理规则
属于 [Preview inventory](../../distribution/server/PREVIEW.md#inventory-and-process-lifecycle)。
启动成功首先证明身份与连接，不等于所有 Worker 已满足某个 Pool 的资格，也不等于
业务 Handler 已执行成功。

默认 Preview 使用本次启动拥有的临时测试 scope，正常退出会清理该 scope 的运行数据。
想观察跨重启保留的数据，请按[部署指南](operations/deploy.md)明确使用独立 scope、
work directory 和保留目录，不能把默认临时环境当成持久部署。
已保存的 Batch Checks 配置文件不会因普通重启自动恢复为新 seed。

在启动终端按 Ctrl+C 正常退出。启动器关闭自己拥有的进程；明确指定的 scope、
Worker inventory 和业务配置仍按各自 Owner 的规则保留。不要通过删除整个 Redis
数据库来“重置演示”。

## 其他入口

- 已有 Runtime/Preview ZIP：使用[包内启动](operations/deploy.md#从发行包启动)。
- 只需要干净 Server/Adapter：使用 `--profile consumer`，再显式提供资源和 Worker。
- 只看页面：公开 Mock 演示不需要 Redis 或 Worker；其显示不能作为真实执行证据。
- Android 或混合 Worker：使用[部署指南](operations/deploy.md)，沿用已有设备和独立 APK。
