# Message Campaigns 0.1.0-preview

Status: current Message Campaigns business owner.

Messages 是有限消息触达业务，用来验证 PRECOMPUTED Task 的发送执行与 Task 结束后的持续业务观察。
与 [SMS Reception](../sms-reception/README.md) 共同运行时，两产品使用同一 Server、Redis scope、
Adapter 和真实 Worker 池；产品之间没有代码依赖。

## 依赖与执行

`backend -> server_jvm` 只消费 `WorkerGroupRegistrationService`、`TaskCreationService`、
`TaskDataService`、`TaskLifecycleService` 及现有 Task 契约。产品不调用 Controller，不经过平台 HTTP
等待器，不创建 Redis、Kernel Owner、Pacer 或 Adapter。只有 `message-campaigns` profile 启用业务。
[Distribution](../../distribution/server/README.md) 提供唯一混合 Group `demo-sim` 与完整业务/共享事件声明。
单产品和双产品都使用 `demo-sim`；两产品幂等消费同一 Group 声明。

```text
POST campaign -> 整批校验和本轮 requestId 幂等 -> 有界提交队列
  -> 创建有限 PRECOMPUTED Task -> 每次最多 100 Items -> 全部确认后批准
  -> Kernel / Matching -> 共用 Adapter -> 实际 Worker message.send
  -> 模拟通道创建唯一消息 -> SENT 执行 Result
收件端 deliver / read / reply -> 本地事实 -> 原 Worker run 的 Reporter
  -> 原 TaskItem 的 Outcome / Result -> 一个轮转观察循环 -> Messages 页面
```

一个收件人对应一个稳定 messageId 和 TaskItem；收件人地址与 Worker 身份无关。
匹配规则始终包含 `worker.messaging.enabled = "true"`，可选的发送号码增加 `worker.phone` 等值条件。
所有国家使用同一 Group。PRECOMPUTED 规则包含 `worker.country $eq`、
`worker.messaging.enabled $eq "true"` 和可选 `worker.phone`，产品不选 Worker。默认候选数 100，可通过
`xa.mass.messages.maximum-candidate-workers` 在 1..1000 内设置。

## API 与业务记录

| API | 契约 |
| --- | --- |
| `GET /api/v1/messages/catalog` | runId、版本、支持的 CN/US/GB（均指向 `demo-sim`）和容量；仅作为可用性观察 |
| `POST /api/v1/messages/campaigns` | `requestId,name,country,body,recipientIds`，可选 `senderPhone`；HTTP 202 返回本地批次 |
| `GET /api/v1/messages/campaigns` | 批次分页，offset 默认 0，limit 默认 30、范围 1..1000 |
| `GET /api/v1/messages/campaigns/{id}` | 提交状态、Task 身份、消息数、分阶段统计 |
| `GET /api/v1/messages/campaigns/{id}/messages` | 消息分页、实际 Worker/号码和最新完整快照 |
| `GET /api/v1/messages/metrics` | 本轮提交、观察、失败、队列和延迟统计 |

每批 1..1000 个唯一收件人。requestId/name/recipientId/senderPhone 最长 128 字符，正文最长 4096。
空白发送号码表示平台选择。整批参数先校验；相同 requestId 与内容返回原批次，内容不同返回 409。
国家不合法、重复收件人和未知字段返回 400；本轮容量或提交队列耗尽返回 429。

受理不代表发送完成。提交状态为 `SUBMITTING / SUBMITTED / SUBMISSION_UNCONFIRMED`。
任何创建、追加或批准失败/结果不明，保留原业务身份和已知 Task 身份，不重新创建 Task、不自动重发。
可能已有部分 Items，甚至批准已发生；未确认批次仍查询已知 Task，显示实际观察。

`SENT` 是模拟通道已由实际 Handler 受理的完整快照；7/8/9 分别用于 `DELIVERED / READ / REPLIED`。
快照包含 campaignId/messageId/recipientId/country/body/workerId/phone/status/observedAtMillis；回复增加
reply 与 replyRequestId。允许首次读到后续阶段，同阶段只接受更晚时间，旧阶段或旧回复不能覆盖新内容。
产品不改实例级 Outcome 名称，不把 `Result=succeeded` 一律当送达。未观察到回执不推断未读或到期。
发送结果齐全后仍轮转读取原 Items；Task 自动完成或显式关闭不结束业务观察。

Backend 与 Host 各最多保留 50 批、50,000 条消息。Backend 用两个提交执行线程、有界 8 项等待队列，
一个每 100ms 轮转的观察循环；每轮一个 Task、最多 1000 个 Result ID。无每消息线程或持久化。
关闭先拒绝新业务，再在共享 5 秒预算内停止提交和观察；不会替业务重试或清理平台 scope。
指标为本轮有限记录的观察延迟分位数，不是生产 SLA。

## 模拟收件端与交付

[Worker Simulator](../../worker_simulator_jvm/README.md#messages-and-shared-products) 拥有模拟通道、消息去重、
收件动作和 Reporter 关联。Backend 和测试输入不能直接创建收件记录。后续回执由业务动作产生，
不是任意 Report 注入。停止 Worker 清理 Reporter；重启可继续本地阅读/回复旧消息，但不能更新旧 Item。
本版无第三方通道、聊天历史、重启恢复、回执 ACK、可靠补偿或跨进程幂等。

统一前端在 `frontend/src/message-campaigns/`，页面为 `/messages`、`/messages/campaigns/{id}`、
`/messages/metrics`。与 SMS 分别观察 catalog，标签保留输入，离开产品停止轮询并中止请求。
源码与 ZIP 均使用 [双产品 Preview](../../distribution/product-preview/README.md) 的同一个启动入口。

## 检查与验收

```powershell
.\gradlew.bat :products:message-campaigns:backend:test :worker_simulator_jvm:test
.\gradlew.bat :distribution:server:productCompositionIntegrationTest
python integrations/product-coexistence/run_proof.py --build --scenario functional
python integrations/product-coexistence/run_proof.py --scenario lifecycle
python integrations/product-coexistence/run_proof.py --scenario load-1k
```

[Product Coexistence](../../integrations/product-coexistence/README.md) 拥有普通 CI 的 12 Worker 闭环和显式
1000 Worker 固定负载。组合证明验证四种 profile、共享资源、实际部分提交和迟到执行结果；真实进程
runner 只使用业务 API、公开 Runtime API 和 Lab 输入。平台通用单调性仍归原 Redis/Runtime Boundary 证明。
