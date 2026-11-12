# 4.2.0 同一发布工具的分档结果

2026-11-12 UTC；提交 `aaed8d648c19e2f6332f37b4e831b0b194d5553b`。
使用 [v4.2.0](https://github.com/quiaingmuarua/xa_mass_platform/releases/tag/v4.2.0) 发布 kit，未重新构建或修改 JAR。
kit SHA256：`8edd9965b8a33dbf58920d50629927eab8e6e3ad3e3c8bcfa257142147d41cf9`。

同一入口和 JAR，只改 Worker 数量及对应连接门槛。每个主档均为10 Task × 5,000 Items，正常TLS、公网HTTPS/WSS；没有SSH隧道或DNS修改。这是分档诊断，不替代原15k/10k验收；[此前失败记录](./consolidated-capacity-report.md)保留。

| 主档 | 结果 | 批准到全部终态 | 观测完成率 | GCP三服务平均CPU |
| --- | --- | ---: | ---: | ---: |
| 100 Worker | 100连接稳定，50,000结果全部核对 | 98.727秒 | 506.45项/秒 | 0.879核 |
| 1,000 Worker | 1,000连接稳定，50,000结果全部核对 | 28.694秒 | 1,742.52项/秒 | 1.773核 |
| 10,000目标 | 6,404次start确认、2次未确认；建连中止，零Task | 无 | 无 | 建连期0.655核 |

核对包含消息身份、原始Item/Result、独立UTF-8 MD5、Task终态及完整导出。100档预置9.107秒、验证61.810秒(含导出7.473秒)；1,000档预置9.072秒、验证64.454秒(含导出9.917秒)，均不计入执行速率。执行前后60秒稳定窗口、首次进展120秒、无进展90秒、执行900秒未放宽。

现有工具固有两个case，故运行100→99、1,000→999、10,000→9,999。附带99和999各自也验证50,000结果，分别100.735秒、28.730秒；不混入主档速率。合计40个Task、200,000个结果通过。10k稳定连接门槛为原计划的9,900，尚未进入该验证阶段。

## 万级阻塞点

10k ramp为140.780秒。两次start于12:05:36.551和12:05:36.672 UTC发出，约10秒后由本地Host返回500；runner标记unconfirmed并停止。本次GCP对应窗口的6,404次Prepare全部200，Nginx错误0。本地Host500不等于公网API500，start确认也不等于稳定连接。

100/1,000档Runtime RSS峰约1424/1429 MiB。10k建连期三服务平均/5秒峰值0.655/1.168核，Runtime RSS峰1432 MiB、FD峰5581；失败前约30秒三服务平均0.233核。没有swap、内存节流/OOM、Redis拒绝或eviction增量。未见这些资源耗尽，但不能据5秒观测排除瞬时网络问题或确定DNS/代理根因。

当前工具将start底层错误压成通用Lab500，首次未确认即中止。前次JFR捕获过Socket.connect超时，本次未新增JFR，不能视为这两个调用的逐条堆栈。后续修复应围绕已有启动路径的错误证据与有界状态核对，不建立分档实现或重放业务Task。当前没有万级执行性能或Server极限结论。

## 环境与清理

客户端WSL Ubuntu/Java21，FD65536；GCP保持同一4.2.0 Runtime、scope和容量配置，无重启或配置改动。同scope保留历史Task，内存差异不是纯Worker成本。

三轮Host/Harness和只读采样进程均停止，临时凭据副本清除，WSL端口范围恢复32768–60999。采样完整结束：282个样本、最大间隔5.014秒。没有DNS修改、长期scope清理或源码改动。

本地安全证据位于 `build/external-release-load/staged/`：summary、receipts、timeline、客户端/GCP资源及SHA256，不含凭据、载荷或私有Worker身份清单。

发布JAR指纹：

- `lib/xa-mass-worker-simulator-jvm-4.2.0.jar`：`7bceed182335132905cba2c56cb8657eeac525e00b4a0b71b7a583d2c0a03e80`
- `acceptance/loaded/lib/xa-mass-worker-loaded-recovery-4.2.0.jar`：`d8bca6fa49254031af1e5f522b47db8e736b1db943da8fcc3e78d0a3d9717d35`
