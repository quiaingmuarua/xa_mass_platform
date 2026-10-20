# 验证：每种证据能说明什么

Status: current proof navigation guide.

先明确要证明的行为，再选择最小且可信的 lane。代码位置、测试名称、CI 配置和一次
成功 run 不是同一种证据；“文档构建通过”也不能说明业务运行正确。
精确 claims/nonclaims 由 [Proof Registry](../testing/proof-registry.md)维护，
命令和 CI 选路由 [TESTING](../../TESTING.md)维护。

## 选择证明

| 要确认的事情 | 首先看哪类证据 |
| --- | --- |
| 算法、参数校验、合法状态、受控并发次序 | focused Owner JVM tests |
| Redis 原子性、精确 fence、实际命令成本 | real Redis Owner |
| HTTP、DTO、Adapter/Worker 协议跨界 | Runtime Boundary |
| Worker 身份、路由、Properties 与能力闭环 | Worker Correctness |
| 活属性变化下真正选中并执行的 Worker | Worker Dynamic Matching |
| 已建立故障后的命名工作与状态恢复 | Worker Convergence Health |
| 负载、Worker 收缩、多次重启和资源稳定 | Worker Loaded Recovery |
| Android 进程、原 run Reporter 与设备交互 | Android Host / APK / Emulator 对应阶段 |
| 业务转换、后续回执、多个场景共存 | Scenario composition 与 source/ZIP acceptance |
| 特定环境中的延迟和完成吞吐 | Worker Call Performance |
| 文档入口、链接与站点交付 | Docs Contract、文档站构建与访问检查 |

大场景不自动覆盖小而精确的原子性或异常断言；几千 Worker 的连接成功也不能替代
某个 Handler 真正执行的证据。

## 在改代码前看选路

```powershell
python .github/scripts/explain_proof_selection.py --base origin/main
```

它解释分支改动会选哪些 proof，不运行这些 proof。随后按
[Selection Decision](../../TESTING.md#selection-decision)执行受影响 Owner 检查和必需边界证明。
Proof Gate 要求被选中的 lane 成功、未选中的 lane 正确跳过；不能用局部检查
声明整个 gate 已通过。

纯说明改动使用 Docs Contract 与站点检查；修改构建、打包或执行行为时，
仍按既有 claim-based 路径选择对应检查。文档站不新增第二套运行时正确性体系。

## 如何读 nightly

性能 nightly 在固定环境执行 open-loop 和 saturation。open-loop 观察指定到达率下的
响应与最终结果；saturation 在深 backlog 中测窗口完成吞吐。
读取时一并核对 commit、环境、配置 hash、measurement/lane 版本、状态分布和重复次数。
详见[能力页](capabilities.md)及
[Performance Owner](../../integrations/worker-call-performance/README.md)。

Loaded Recovery 则在规定的 Worker 数量、故障和重启次序下验证完成与资源稳定。
它不寻找系统最大容量，也不提供持续无限时长的 SLA。
两条 nightly 都不能用一个绿色图标替代具体 workload 和独立断言。

## 记录结论

报告应明确：检查命令、被测 commit/工作区、实际环境、执行结果、产物位置和没有覆盖的边界。
CI run 只属于它的被测 commit；新代码或不同配置不能沿用旧通过结论。

测试使用独立 `test_*` scope，并只清理该精确 scope。执行前阅读
[Shared Infrastructure](../../TESTING.md#shared-infrastructure)，不复用生产数据，
不通过重试失败 case、扩大样本或修改阈值来制造“通过”。
