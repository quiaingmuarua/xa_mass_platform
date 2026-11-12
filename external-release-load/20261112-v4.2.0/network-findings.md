# 4.2.0 公网建连故障定位与 GCP 隔离验证

日期：2026-11-12 UTC。Runtime 与 Simulator kit 均为已发布4.2.0、提交`aaed8d648c19e2f6332f37b4e831b0b194d5553b`。没有修改发布JAR、关闭TLS认证或更改任务规模。

**结论：GCP已通过万级连接下的真实有限任务隔离验证。直接公网失败发生在到达GCP guest之前的TCP连接路径；两次GitHub runner都呈现完整4096公网源端口块耗尽/回收形状，高度指向客户端出口SNAT预算。直接公网15k/10k验收仍未通过。**

## 实际执行结果

| 路径 | Worker | 工作量 | 独立核对 | 批准到全部终态 |
| --- | ---: | ---: | --- | ---: |
| 本机直接公网，之前分档 | 100 | 10×5000 | 全部成功、MD5及完整导出通过 | 98.727秒 |
| 本机直接公网，之前分档 | 1000 | 10×5000 | 全部通过 | 28.694秒 |
| SSH传输隔离，同一TLS网关 | 10000 | 10×5000 | 全部通过，最少10000连接 | 29.884秒 |
| 同一隔离实验附带收缩 | 9999 | 10×5000 | 全部通过，最少9999连接 | 29.893秒 |

SSH实验仅把大量外部TCP连接汇聚在一条受控通道中，GCP仍处理原TLS、鉴权、Nginx、Adapter、Pacer、Pool和任务。它证明GCP能够承载这份万级连接工作量，**不替代直接公网容量验收**。两轮共100000个结果与完整JSONL独立核对。10k主档约1673项/秒，预置12.398秒、结果核验61.135秒(含导出6.414秒)另计，不是Server极限或Item P99。

10k执行窗内Runtime/Nginx/Redis合计CPU平均1.475核；不含sshd和其他进程，不能当成全VM CPU。Runtime RSS峰约1463MiB、FD10092，无swap、内存节流/OOM或Redis拒绝增量。GCP配置及长期scope未因该实验改变。

## GitHub 客户端与 GCP 同时取证

[带诊断的运行43241782359](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/43241782359)保持完整15k/10k参数和原退出码。诊断采集完整，TCP kernel drop=0；负载仍是unconfirmed，没有创建Task。

- 客户端去重后4574个TCP连接尝试，其中4560收到SYN-ACK、14没有收到。
- 4560条正常流的初始序号全部在GCP入站抓包匹配；14条失败流的SYN没有一条到达GCP网卡。
- 相同14个端口出现在Host PID的SYN-SENT中；3次Prepare失败对应JFR `SocketTimeoutException → timedFinishConnect`，约10秒后发生。
- 不是DNS失败，不是已到达Nginx后的TLS、认证或Runtime处理错误。`-i any`会重复观察同一包；按元组和初始序号去重后121次SYN重传，与内核计数增量一致。

| 独立运行 | GCP看到的公网源端口块 | 唯一端口数 |
| --- | --- | ---: |
| 43231339401 | 30728–34823，连续、零空洞 | 4096 |
| 43241782359 | 57528–61623，连续、零空洞 | 4096 |

guest实际端口范围已是10000–65535，但公网端口全部被地址转换改写。块尾端口首次使用后出现失败，少数后续成功连接复用块内旧端口。这高度指向hosted runner出口SNAT分配耗尽/等待回收；增大guest端口范围不会增大云端SNAT分配。微软的[SNAT说明](https://learn.microsoft.com/en-us/azure/load-balancer/load-balancer-outbound-connections)描述了同目的IP/端口需要独立SNAT端口以及耗尽后的新连接失败。

归因边界：没有读取GitHub底层NAT配置，不能把所有hosted runner永久定义为4096上限。GCP现有service account的Compute API scope不足，生效防火墙查询403；未擅自改IAM。两端证据证明故障在guest外，4096块形状强烈支持出口SNAT解释，但没有宣称完成云网络每一跳的审计。

## 本机路径单独处理

本机为WSL NAT→gVisor/TUN→DIRECT TCP。实际不是代理节点，但也不是原TCP透明透传。失败复现中332条发往198.18.1.105:443的SYN没有应答，出现2330次重传；GCP收到的入站SYN均及时应答。Windows自身TCP动态池只有1024–15000，并有运行期变化的保留区间。端口预算高度可疑，但尚未完成管理员扩大端口池的因果对照，不把它冒充已验证修复。

下一步保持万级目标：用足够出站/SNAT预算的外部执行机；本机先扩大Windows端口池再直接运行原JAR，CI需合适的runner网络，而不是升级GCP或缩小工作量。

## 排查副作用及修复

13:28以root执行`nginx -T`时，我漏带了服务实际的`user www-data;`全局参数，导致五个临时目录变成nobody所有；随后第一次隔离实验追加返回500。这是排查操作引入的独立副作用，不是原TCP故障。已恢复目录权限并核对写入，修正同样有隐患的证书续期脚本和OPERATIONS说明。没有重启Runtime/Nginx。失败任务已按原runner明确关闭，失败证据保留。

## 交付和清理

[PR95](https://github.com/quiaingmuarua/xa_mass_platform/pull/95)已合并：现有手动流程增加默认关闭的network_diagnostics观察开关，不改变Release命令、参数、JAR、重试或验收阈值；只上传白名单TCP/JFR元数据，私有录制删除，保留原失败状态。64项Python回归在Windows通过(2项平台跳过)，新增14项在Linux全部通过；[main所选检查](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/43241645191)通过。手动公网容量运行保留失败，未改写为成功。

本次Host/Harness、隧道和采样进程已停止，临时凭据/SSH密钥副本删除，本地hosts和WSL端口范围恢复，临时SSH会话FD限额恢复。GCP Runtime、数据、日常凭据保留。原始安全证据与SHA256位于本地`build/external-release-load/network-diagnosis/`，没有上传凭据、业务载荷或私有Worker身份清单。
