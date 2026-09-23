# Task 指定 Worker 2k：Result 闭合失败归因

[手动运行 35838688415](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/35838688415)
在 HEAD `b3b0fa14568bc54ef0844f89f3c4895217507111` 上执行 `rpc-diagnosis`，
**assignment ceiling 为默认 100**。七例中只有 `rpc-targeted-2000` 失败：
224,447 个已受理 Item 中有 37,961 个在固定 180 秒补查后仍未观察到 Result。
`rpc-any-2000` 同样过载但补查约 130 秒闭合。

结论：闭合断言本身正确，它暴露了一个机制问题，并叠加了 180 秒在超出单 Task
预算时余量过薄的问题。本次仅把超出预算时的闭合时限改为由已文档化的预算推导，
闭合断言不变；机制问题未修改，需另行授权。

## CI 证据

- 补查阶段按 Item 到期时间从新到旧推进：未闭合的全部是测量开始后约 0～50 秒
  提交的最早批次。
- 失败 Result 的结算速率稳定在约 1,000 条/s，即单 Task `10 × B` 的检查预算。
- `rpc-any-2000` 补查成功速率稳定在约 1,000/s；`rpc-targeted-2000` 从约 450/s
  逐段降到 23/s，前 90 秒几乎只靠成功推进，之后才按 TTL 结算失败。
- 测量窗口末段每个 5 秒批次恰有 20 个失败 Result，而 2,000/s 轮转到 1,000 个
  Worker 时每个 Worker 每 5 秒 10 次调用。60 秒之后计划的已受理 Item 中，
  **恰有 2 个目标 Worker 的 120 次调用全部失败**，其余 998 个都有成功。

## 本地复现（非参考主机）

Windows WSL Ubuntu、Java 21.0.12、Docker Redis 7.4.10，同一 HEAD 产物，
`--allow-nonreference-host`，runner 进程组固定到四个 CPU。旁路脚本每 3～5 秒
通过公开 `workers:scheduling-observe` 和 `workers:network-observe` 读取全部
1,000 个 Worker 的状态（仅用 Worker Score ZSET 成员列出身份，不解码 Score）。

| 运行 | ceiling | Redis CPU | 闭合 | 补查闭合约 | 全失败目标 Worker |
| --- | ---: | --- | --- | ---: | ---: |
| CI 35838688415 | 100 | 共享 | 失败，37,961 未观察 | >180 秒 | 2 |
| 本地 | 100 | 未固定 | 通过 | 137 秒 | 0 |
| 本地 | 100 | 同四核 | 通过 | 140 秒 | 0 |
| 本地 | 1,000 | 同四核 | 通过 | 43 秒 | 1 |

ceiling 1,000 的本地运行中，旁路观测到 1 个 Worker 自约 40 秒起一直是
`recovery`，同时网络投影一直是 `connected`，到案例结束未恢复；它在 60 秒后
收到的 120 次指定调用全部以 TTL 失败结束。三次本地运行都只采样到公开投影，
没有 Server 日志能直接说明该 Worker 由哪条证据转入 RECOVERY。

## 机制链

1. 过载期间个别 Worker 在保持连接的情况下转为 RECOVERY。按代码路径，只有
   Network Evidence 通道能写入 RECOVERY；DEFAULT 无周期 Probe，最可能的来源是
   Adapter 对超过执行期限（派发时间 + 5 秒）的 TASK Command 发出的
   `worker-delivery.expired`。**这一来源是推断，未取得逐条证据。**
2. DEFAULT 不做 Probe，连接没有变化就没有新的 CONNECTED 证据，所以该 Worker
   持续不可执行。指定该 Worker 的 Item 只能在 TTL 后结算为失败。
3. Task Dispatch 每轮按到期时间降序读取最多 B 个 Item；未能取得执行权的 Item
   保持原坐标，因此持续占据最新的窗口。B=100 时，两个不可执行的目标约在处理
   5 万个 Item 后占满窗口，成功推进衰减到接近零，直到窗口内 Item 过期。
   B=1,000 时窗口大十倍，一个不可执行目标不足以占满，闭合在 43 秒内完成。
4. 即使没有不可执行的 Worker，B=100 下约 14 万个积压也需要约 140 秒才能结算，
   在 180 秒内只剩约 25% 余量。

第 1、2 点符合调度主线对 liveness 缺陷的描述：到期工作与已连接的空闲 Worker
在多轮中无法形成分配。第 3、4 点是已文档化的单 Task 预算与到期顺序的组合，
不是新增缺陷，但会放大第 1 点。9/09 正式运行中六个 2k Task 案例的闭合失败早于
本次 ceiling 改动；本轮没有证据表明 ceiling 提交引入了回归。

## 本次 proof 调整

- 保留"每个已受理 Item 必须观察到 Result"的断言与失败 Result 不计成功的口径。
- offered 不超过 `10 × B` 时仍为 180 秒；超出时补查时限为
  `max(180, 120 + ceil(已受理数 / (10 × B)))` 秒。B=100 的 2k 案例约 345 秒；
  定时 100 Worker Task suite 与 B=1,000 的全部案例保持 180 秒。
- 证据新增 `drainBudgetSeconds`、`followupObservationMaxMillis`，以及指定 Worker
  案例的诊断字段 `targetsWithoutSuccessAfter60Seconds`。该字段不是门禁；
  非零即表示存在连接中却始终无法执行的目标 Worker。

这只放宽了闭合的等待期限。它不修复不可执行 Worker，也不建立 2k 容量或
成功率结论；新的 RPC manifest 仍不进入定时计划。

## 未解决与后续

- 取得 Worker 转入 RECOVERY 的直接证据（例如在诊断运行中记录 Network Evidence
  的类型与次数），再决定修复方向。
- 修复选项涉及 Serviceability/DEFAULT preset 或 Task Dispatch 的 Item 选择，属于
  核心调度行为变更，需要单独授权、对应 Owner 文档与 Redis/Runtime 证明。
