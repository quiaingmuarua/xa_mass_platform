# 验证：每种证据能说明什么

Status: current proof navigation guide.

先明确要证明的行为，再选择最小且可信的 lane。代码位置、测试名称、CI 配置和一次
成功 run 不是同一种证据；“文档构建通过”也不能说明业务运行正确。
精确 claims/nonclaims 由 [Proof Registry](../testing/proof-registry.md)维护，
命令和 CI 选路由 [TESTING](../../TESTING.md)维护。

日常功能开发在本地和 CI 中取得所需证据，包括真实 Redis、Server／Adapter、
Android 设备和测试网关。认证或公网连接能力的改动也不默认依赖 GCP。
明确的部署或公网验收任务才增加现场证据；普通 PR 不因缺少 GCP 验收而未完成。

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
| 某个 Release 在 GCP 是否可用 | 实际安装版本/配置、外部 Worker 与受影响功能的小规模现场验收 |

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

## Release Preview 的现场证据

本节用于明确的部署、现场诊断或公网验收任务。
[GCP Release Preview](operations/deploy.md#release-preview) 按 Release 推进；普通 PR
只按既有规则选择本地／CI 检查，不附加主机访问或现场验收。发布所需的完整 Proof
要求保持不变。GCP 消费已发布制品并验证真实部署，不在主机重复完整 proof。

| 已取得的证据 | 可以说明什么 | 不能代替什么 |
| --- | --- | --- |
| CI 通过 | 对应提交、配置和所选 workload 的断言通过 | 制品已发布、GCP 已部署或现场可用 |
| Release 发布 | 固定版本及制品已交付，可核对来源与指纹 | 当前主机已经安装该版本 |
| 服务启动/就绪 | 该进程达到其启动检查条件 | Worker 在线、入池或 Handler 实际执行 |
| API/Worker 执行与结果核对 | 被测任务走通了已记录的真实链路 | 未执行的浏览器操作、Android App 或其他外部环境 |
| 网页交互验收 | 实际操作过的页面、任务步骤和下载可用 | 未覆盖的场景或长期 SLA |

主机部署记录分别保留通过、失败、待验收和未覆盖的事实，不把文档更新当成新运行证据。
在部署或公网验收任务中，根据实际部署版本、配置及客户端变化选择现场验收范围；
仓库中的普通改动本身不会启动这项工作。
发布前核对所需 Proof，PR 收尾同时核对 main 自身的最终检查汇总；独立手动 run 通过
不能消除另一份被取消检查的状态。不要在 main 自动检查未结束时启动会取消它的手动发布 Proof。

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
