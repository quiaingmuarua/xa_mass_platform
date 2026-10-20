# 使用 SMS Reception

Status: current SMS reception user guide.

SMS 为一次号码申请保留原 messageId，随后用同一个身份观察最新短信。
Preview 的短信输入是模拟的；调度、Worker SDK、结果存储和查询经过真实平台。

## 取号和收码

1. 在启用了 SMS 的真实环境中以管理员身份打开 `/sms`。
2. 选择应用、国家和接收时长，发起一次取号。
3. 保存返回的 messageId。观察到号码后，页面显示 phoneNumber、租期截止时间和接收状态。
4. 使用独立 Host `/lab` 页面输入模拟短信；Console 本身不调用设备 Host API。
5. 页面对当前活动接收每秒刷新一次，首次收到短信后仍可继续显示最新短信。

查询还没有观察到结果时，继续使用原 messageId。重新点击取号会创建另一个 Item，
不是对原申请的查询或恢复。页面支持手动输入 messageId 查询；没有取消或续租操作。

## 状态含义

| 状态 | 如何处理 |
| --- | --- |
| `NOT_OBSERVED` | 尚未取得结果，包括未知 ID；保留原身份继续核对，不等于执行失败 |
| `WAITING` | 已取得号码，尚未观察到短信 |
| `RECEIVED` | 保留最新短信；租期结束后也可能继续显示最后观察值 |
| `EXPIRED` | 接收窗口已结束，尚未观察到短信；不能据此断言物理设备从未收到 |
| `FAILED` | 明确执行/业务拒绝或无效业务快照，按错误信息处理 |

`leaseActive` 是与截止时间比较的结果，不能证明 Worker 当前在线或可用。
离开 SMS 页面会停止本地轮询，不会重新提交取号。

## 保留边界

浏览器当前保留最多 100 条 session 记录，Server 重启不会自动删掉这些 messageId。
Server 的短期内存快照与 Redis Result 消费是不同的状态：
已经取得的内容可能因重启、淘汰或跨实例访问而无法再次读取。
业务结果不是永久历史记录，也没有 ACK 或自动重放机制。

需要留存的 HTTP 回执由调用者保存；不要把一次重启后的空查询当成“从来没有收到短信”。
取号时长、缓存大小和 TTL 等完整约束由
[SMS Owner](../../../scenarios/sms-reception-jvm/README.md)维护。

`/sms/metrics` 展示有界调用延迟、错误和投影/库存观察，不枚举全部订单。
公开 Mock 不启用 SMS 实际请求；未开启 SMS 的平台环境也会显示场景不可用。
