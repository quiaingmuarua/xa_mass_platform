# 复杂项目里的 Vibe Coding 复盘

这份复盘不讲 AI 写了多少代码，而是讲这段时间我怎么用 AI 持续迭代这个项目，以及哪些做法有效，哪些地方后来又不得不收回来。

## 先说项目背景

这个项目当前是一个通用的分布式任务调度平台。它不是简单 CRUD 后台，也不是单一 worker demo。现在已经有几条比较完整的能力线：

- 任务主线：`Task shell -> item append -> runtime enqueue -> dispatch -> result convergence -> task state`
- 生命周期和状态机：不只是成功/失败，还有 terminal reason、final reason、retry、expiry
- runtime 能力：ready queue、active lease、retry scheduling、watchdog
- transport 能力：polling、websocket、socket
- 接入方式：SDK-first，同时有 HTTP / server shell 做验证
- 验证体系：E2E、integration、concurrency、perf、chaos、CI

所以这个项目消耗 token 的原因，不是单纯代码多，而是语义密度高。很多讨论不是“接口怎么写”，而是：

- 真相到底放 runtime、storage 还是 trace
- 生命周期语义有没有漂
- transport shape 会不会反向污染 kernel
- 旧路径是不是还在持续影响新改动

项目现在大概处在这样一个状态：

- 主线已经比较清楚
- 能力已经不弱
- 测试和文档基线已经开始成形
- 但结构收敛和边界治理仍然是日常工作的一部分

## 回头看，这个项目里做得比较对的几件事

先说结果。现在回头看，我觉得有几件事是做对了的。

**约束前置。**  
`AGENTS.md`、baseline、owner README 不是装饰，而是先把主线、边界、误读点、验证要求写出来，再让 agent 进入实现。

**单一真相的方向一直没丢。**  
虽然中间有很多残留和来回，但大的方向一直是清楚的：runtime 的真相归 runtime，storage 的真相归 storage，trace 负责历史和分析，不让 projection 重新变成主线 owner。

**主线没有被功能堆散。**  
很多重构不是在加层，而是在做主线收敛、概念收口和路径变窄。

**测试和 trace 不是补票。**  
E2E、integration、concurrency、chaos、CI、trace、状态审计，这些东西已经开始承担“重构前提”的角色，而不是最后才想起来补。

**文档开始承担控制面作用。**  
这点很重要。文档不是介绍材料，而是跨 session、跨 agent 维持理解一致性的基础设施。

## 我一开始是怎么推进的

这里说的“放开让 agent 写”，不是让它乱写，而是在有约束的前提下放大推进速度。

**稍复杂的修改尽量先走 `plan mode`。**  
不是所有改动都开 plan，但只要改动稍微复杂、影响面开始扩大，我就更倾向于先看 plan。重点不是形式，而是看它是否真的理解 scope、影响面、风险点和验证方式。

**一个 session 尽量只专注一个模块。**  
我尽量不让一个 session 同时在 engine、transport、storage、server 之间频繁切换。这样做的好处很直接：上下文更稳，owner boundary 不容易混，token 更省，agent 记忆更不容易乱。如果确实要跨模块，我会单开一个 session。

**保留一个全局 session。**  
虽然我强调模块 session，但同时一定保留一个全局 session。局部 session 容易优化局部，而复杂项目更难的是全局判断。

**单独开 session 做文档 / 架构 review。**  
这个 session 不负责具体实现，只负责看：文档是不是还代表当前 reality，baseline 是否和代码一致，某次重构之后错误概念有没有继续固化。

**定期用新的 agent / 新 session 做第三方 review。**  
长 session 很容易形成路径依赖。新开一个 agent 的价值，不只是 code review，而是重新判断：这个项目现在是否还 agent-friendly，一个新上下文能不能快速抓住主线，某些约束是不是只存在于旧 session 的记忆里。

**一定让 agent 结合动静态验证。**  
不能让 agent 只是看代码、改代码、靠静态阅读判断对错。复杂项目里必须尽量让它结合测试执行、集成验证、日志、trace、状态审计。

## 一个后来越来越强的习惯：保持 CI 节奏

还有一个对我帮助很大的习惯是：尽量保持每天至少有一次全绿 CI。大的重构之后，我会要求尽快回到全绿。

平时 agent 做局部改动时，我会先要求：改动模块能编译、相关测试通过、关键主线验证不破。

整个项目的全量 CI 可以稍微松一点，不需要每个小改动都立刻拉满，但不能长期失绿。否则项目很快就会失去“当前整体是否还站得住”的判断能力。

## 中间一个很关键的问题：单一真相

复杂系统里，如果同一类事实长期有两三个真相来源，AI 协作几乎一定会越改越乱。

这个项目里，我一直比较坚持：runtime 的真相归 runtime，control-plane 的真相归 storage，高体量历史和分析归 trace / audit，projection 可以有，但不能重新变成主线 truth。

`TaskMsg` 是一个很典型的例子。

我很早就想让 agent 废除 `TaskMsg` 作为当前主线路径里的核心 owner shape，但移除不彻底，旧代码、旧测试、旧命名一直残留，它也一直牢牢占据 agent 的心智。结果就是：虽然方向上想废除，但实际上它反而越加越多。

这件事让我更明确地意识到：

> 想废除一个旧主线概念，不能只在口头上废除，必须在代码、测试、文档、命名和调用路径上一起做真正收敛。

## 后来逐渐补上的约束系统

我现在主要依赖这些东西来约束主线：结构化日志、状态审计、E2E、trace、integration + CI、baseline / owner README / handoff 文档。  
这些东西不是附属品。对复杂项目来说，它们就是高速迭代的刹车系统。

## 怎么省 token

这个项目里，省 token 不是靠少问几句，而是靠减少无效重对齐。

**不要随意开新 session。**  
新 session 不等于更高效。每开一个新 session，都要重新建立对项目、主线、约束和当前变更的理解。只有在当前 session 已经对齐主线、并且确实需要独立视角或独立模块推进时，再开新的。

**一个 session 尽量只压一个模块。**  
这样上下文稳定，agent 不容易在 engine、transport、storage、server 之间来回漂。

**跨 agent 协同先做文档交接。**  
不要把交接成本放在聊天里临时补。提前把 baseline、模块入口、当前主线、误区、验证方式写清楚，后续会省很多 token。

**复杂修改先 plan。**  
plan 本身会消耗一点 token，但通常能省掉后面因为理解错误而反复返工的 token。

**让验证靠脚本和测试，不靠重复解释。**  
能用 E2E、integration、CI、runbook、trace 看清楚的事情，不要反复靠自然语言来对齐。

## 这段时间我越来越明确的几个判断

**1. agent 是 owner，不是被动执行者。**  
我不希望 agent 只是接命令然后写代码。复杂项目里，agent 必须像 owner 一样去理解当前主线、当前边界、当前风险、当前验证方式。难点在于，agent 默认倾向于“完成任务”，而不是“判断这件事该不该做”。要让它真的扮演 owner，不能只靠 prompt，而要持续把这些判断写进约束文档和 review 规则里。

**2. 先形成判断，再进入实现。**  
我不希望 agent 先长时间争论，再迟迟不进入实现。更准确的做法是：先形成判断，复杂问题先暴露理解模型，必要时指出冲突，主线明确后尽快实现和验证。  
“argue before coding” 是个伪命题。真正的问题不是争不争，而是：它有没有理解清楚就开始动手。

**3. engine 不是 CRUD backend。**  
只要把 engine 误看成普通 backend，就很容易机械套 `controller/service/repository` 思路，把 runtime kernel 的主线冲淡。这个判断我直接写进了 `AGENTS.md`，因为它太容易被忽视了。

**4. 不要机械按 class 大小驱动重构。**  
内部 orchestrator 可以大，大不自动等于 God class。如果 owner boundary 清楚、主线清楚、调度入口清楚，那它就可能是合理的大类。`TaskManager` 实现多个引擎 seam 是当前 owner 设计，不是证明需要再加一个内部 bridge 层的理由。

**5. 没有真实边界，就不要拆 `bridge` / `facade` / `wrapper`。**  
如果新层没有带来新的 owner boundary、协议边界、生命周期边界或稳定调用面，那大概率只是把调用关系打散。这一条我在 `AGENTS.md` 里专门加了一个 `Abstraction Test`：如果一个新层不改变谁做决策、谁可以调用、它保护什么生命周期边界，那它大概不该存在。

## 最后几个比较强的感悟

**非新功能不要只加代码，不澄清旧逻辑。**  
如果不是在做真正的新功能，而是在修正、收敛、重构主线，那就不能只往上加代码。要同时做旧逻辑澄清、路径收敛和文档同步。

**phase 可以有，但必须有 cleanup boundary。**  
我不反对 phase，但必须明确：为什么暂时保留、保留到哪里、谁负责清理、什么边界一过就必须 cleanup。

**rename 只在值得的时候做。**  
rename 只在两类场景真正值得做：语义已经变了，或者旧命名会在热点路径上持续误导人。

**不要只靠 rename 把旧路径“叫成兼容层”。**  
把旧路径改个名字，叫成 `compatibility`、`legacy`、`bridge`，不等于它真的被收敛了。如果主线路径、调用关系、owner boundary 都还在，那这只是 relabel，不是治理。

**agent 容易形成稳定的错误倾向。**  
最近我越来越在意这个问题。agent 的问题不只是偶尔出错，而是会形成一些稳定倾向，比如：看见大类就想拆、看见旧逻辑就想包一层、看见命名不统一就先 rename、觉得多一层就是更优雅。所以复杂项目里，不只是要管理 agent 的输出，还要管理它的思维定势。

## 最后

我现在对这件事的理解大概可以压成一句话：

> 复杂项目里的 vibe coding，不是把开发外包给 AI，而是把 AI 纳入一套有主线、有单一真相、有验证、有文档基线、有纠偏机制的工程系统。

如果只看“写得快”，复杂度迟早会反噬。  
如果把主线、truth、测试、trace、文档和 review 机制搭起来，AI 才真正有可能成为复杂项目的迭代放大器。
