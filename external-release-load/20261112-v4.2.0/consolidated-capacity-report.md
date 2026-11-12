# 4.2 外部容量验收记录

源码 `aaed8d648c19e2f6332f37b4e831b0b194d5553b`；[PR #94](https://github.com/quiaingmuarua/xa_mass_platform/pull/94)、[Release 4.2.0](https://github.com/quiaingmuarua/xa_mass_platform/releases/tag/v4.2.0)。完整 [manual Proof](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/43191523662) 与原 [local nightly](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/43186658267) 是独立证明。

**结论：GCP 4.2 基线已通过；外部 15k／10k、共 100,000 Item 的容量验收未完成。四次正式运行均在 ramp 阶段 `unconfirmed`，没有发送 Task 创建请求，不能报告吞吐。**

| 轮次 | 路径 | 确认 start | 未确认 start | ramp 秒 | Task |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | GitHub [43195452791](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/43195452791) | 4937 | 2 | 222.902 | 0 |
| 2 | GitHub [43197224933](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/43197224933) | 3979 | 1 | 319.620 | 0 |
| 3 | WSL 既有解析路径 | 6451 | 2 | 147.644 | 0 |
| 5 | WSL 临时静态 IP hosts 对照 | 7073 | 1 | 155.585 | 0 |

start 是本地 Host 控制操作回执，不等于连接成功数。各轮沿用发布 kit 和完整固定参数，没有调大 timeout；所有失败均保留。

第 4 轮仅为 startup-only JFR 诊断：UTC **10:28:15.198** 捕获 `SocketTimeoutException: Connect timed out`，堆栈经过 Java `Socket.connect`／OkHttp `Platform.connectSocket`，随后出现 `JavaWorkerManager.prepareAndStart` 的 `WorkerException`，与 Lab 500 对应。它不能证明 DNS、代理或某一网段是根因；静态 IP 对照也未解决。

| 轮次 | GCP 三服务平均／5 秒峰值 CPU（核） | Runtime／Nginx cgroup 内存峰值 MiB | Runtime／Nginx 聚合 FD 峰值 |
| --- | ---: | ---: | ---: |
| 1 | 0.511／1.074 | 1257.5／345.9 | 4116／8212 |
| 2 | 0.256／0.402 | 1277.5／356.6 | 4043／8070 |
| 3 | 0.612／0.840 | 1295.7／439.5 | 5547／11078 |
| 5 | 0.560／0.735 | 1349.8／456.4 | 5260／10499 |

CPU 使用 cgroup 累计量增量除以实际采样间隔，仅统计 ramp，未用失败后的空闲稀释均值。全部样本完整；swap、内存节流／OOM及 Redis eviction／拒绝增量均为 0。没有这三项服务资源耗尽的证据；这些是约 5 秒平均值，未涵盖全部系统进程和短暂尖峰，更不是 Server 吞吐。

本轮 Host、Harness、采样进程已停止；WSL hosts 和原端口范围已恢复。GCP 配置未因解析路径对照修改，长期 Runtime 保留。完整安全字段、原始证据位置与 SHA256 见同目录 `consolidated-capacity-report.json`；不包含业务 payload、凭据或私有 Worker 身份基线。
