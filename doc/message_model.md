# 整体架构

```python
        +--------------------------+
        |   控制中心 (后台指令发起)|
        +--------------------------+
                      ↓
              CommandController
                      ↓
          +------------------------+
          |   指令分发器 Dispatcher|
          +------------------------+
                      ↓
        +-----------------------------+
        |  指令队列（设备消息中心）   | ← 可持久化 & 支持优先/依赖
        +-----------------------------+
                      ↓
        +--------------------------+
        | WebSocketSessionManager  |
        +--------------------------+
             ↓             ↓
         手机1(WebSocket)  手机2(WebSocket) ...
```



## 模块划分

### **1️⃣** **ConnectionHub - 多连接角色管理中心**

- **职责**：

- - 管理每个设备 (deviceId) 的多个连接（按 connRole 区分）
- 保证每个 (deviceId, connRole) 只有一个活跃连接
- 处理断线剔除、重连注册、心跳维护

- **参考**：KubeEdge / EMQX

------

### **2️⃣** **DispatcherRouter - 消息分发中心**

- **职责**：

- - 根据 msgType + subMsgType 统一路由消息到正确处理器
- 支持双向通信结构统一（task、response、ack、status、log）
- 按消息方向（from: client/server）分发 inbound/outbound

- **参考**：Temporal Worker 路由器 / gRPC 多 service handler

------

### **3️⃣** **TaskManager - 任务生命周期管理器**

- **职责**：

- - 创建任务（msgId）、步骤（stepId）编排
- 维护每个 step 的依赖、状态、元数据
- 支持批处理、独立下发、串并行执行
- 可接入外部 HTTP / CI 系统发起任务

- **参考**：Airflow DAG 执行器 / DolphinScheduler TaskProcessor

------

### **4️⃣** **StepStatusManager - 状态机控制器**

- **职责**：

- - 管理每个 msgId + stepId 的状态（INIT → RUNNING → SUCCESS/FAILED）
- 控制状态转移合法性（FAILED 可修正为 SUCCESS，SUCCESS 锁定）
- Redis Hash + Lua 脚本实现原子状态与计数更新
- 定期校验状态一致性（重建 counter）

- **参考**：Temporal State Workflow / Kafka Streams 状态表

------

### **5️⃣** **RetryManager - 弱网容忍与任务补发器**

- **职责**：

- - 控制补发逻辑（断线后续发、失败重试、step级别补投）
- 避免重复发（step 加锁、状态判定）
- 支持幂等投递（每 step 仅执行一次）

- **参考**：KubeEdge 断线续投机制 / Redis 事务式任务队列

------

### **6️⃣** **AckManager - 回执跟踪与一致性保障**

- **职责**：

- - 管理客户端返回的 step/all/ack 消息
- 记录任务是否“被投递、被执行、被接收”
- 失败/重试区分网络 vs 业务失败

- **参考**：MQ Ack Tracker / Temporal Callback Handler

------

### **7️⃣** **BICollector - 实时状态统计模块**

- **职责**：

- - 维护每个任务的状态分布计数器（INIT, RUNNING, SUCCESS…）
- 提供实时查询任务执行状态
- 支持 Redis 自动构建、重建机制

- **参考**：DolphinScheduler 状态聚合器 / Prometheus 指标缓存模块

------

### **8️⃣** **MessageModel - 通用消息结构模型定义**

## **项目模块维度全景图**

| **层级**         | **模块**                              | **是否已设计** |
| ---------------- | ------------------------------------- | -------------- |
| 👤 客户端模型层   | 多连接角色/平台类型（iOS/Docker）识别 | ✅ 是           |
| 🔄 通信层         | msgType、连接注册、心跳等结构         | ✅ 是           |
| 🧭 任务调度层     | 多 step 任务、依赖、msgId/stepId 管理 | ✅ 是           |
| 💾 状态管理层     | DeviceStatus + AppStatus、任务状态机  | ✅ 是           |
| 🔁 幂等+重试层    | step 状态转移、失败修正、任务补发     | ✅ 是           |
| 📊 可观测层       | step 状态计数器、任务执行统计         | ✅ 是           |
| 🧠 路由分发层     | DispatcherRouter 统一消息分发         | ✅ 是           |
| 📤 Ack / 回调处理 | 响应级别处理与最终态锁定              | ✅ 是           |



## 优先级

| **阶段**   | **模块**                                 | **优先级** | **可用性影响**  |
| ---------- | ---------------------------------------- | ---------- | --------------- |
| ✅ 第一阶段 | ConnectionHub + 通道管理                 | 高         | 启动基础        |
| ✅ 第二阶段 | TaskStateManager + 状态流转控制          | 高         | 保证任务一致性  |
| ✅ 第三阶段 | SchedulerService + 状态调度判定          | 中高       | 多 app 可调度化 |
| ✅ 第四阶段 | StatusCounterManager + 统计模块          | 中         | 运维观测用      |
| ✅ 第五阶段 | RetryManager + 重连补发机制              | 高         | 弱网容错        |
| ✅ 第六阶段 | 多平台 / 多 app 接入注册 + platform 分层 | 中         | 支持扩展性      |

#  MessageModel

## 设计思路

-  标准化消息格式（双向统一）
-  引入消息路由中心（双向 Dispatcher）
-  Server 可订阅 Client 状态（push 模式）
- 考虑多通道通信、设备连接角色 ，可能一个client有多个role 角色连接,通过connRole进行配置

## 任务消息

```java
{
    "msgId": "T123456",        // 整个任务交互 ID
    "msgType": "task", //"task" | "response" | "ack" | "status" | "log"
    "subMsgType": "group_send",
     "from": "client" | "server",
    "context": {
         "connRole": "messaegs_task"  // 或 "messaegs_xposed" / "tg_xposed"
        "taskId": "BizTask_ABC",
        "retryCount": 0,
         "responseLevel": "step" | "all"
    },//context需要透传的
    "payload":{
        "steps": [
    {
        "stepId": "step-1",
        "action": "createGroup",
        "params": { ... }
    },
    {
        "stepId": "step-2",
        "action": "sendMessage",
        "dependsOn": ["step-1"],
        "params": { ... }
    }]
    }，//参数
    "result": {
    "code": 200,
    "message": "Sent success"
  }。//结果
}
```



## **msgType**

| msgType  | **方向**        | **用途**                 | **示例** subMsgType        |
| -------- | --------------- | ------------------------ | -------------------------- |
| task     | server ➝ client | 任务下发                 | create_group, send_message |
| response | client ➝ server | 任务结果回传             | step, all（阶段、整体）    |
| ack      | 双向            | 任务或回调的确认         | task_ack, result_ack       |
| ping     | client ➝ server | 心跳                     | 无                         |
| pong     | server ➝ client | 心跳响应                 | 无                         |
| status   | client ➝ server | 主动上报状态（非任务类） | device_env, login_state    |
| control  | server ➝ client | 服务端控制命令           | reboot, clean_env          |
| log      | client ➝ server | 上报日志、异常           | exception, stdout, metrics |
| event    | client ➝ server | 客户端主动触发行为事件   | user_click, app_crash      |
| config   | server ➝ client | 配置下发                 | set_proxy, update_version  |



## 设备注册

```java
{
  "msgType": "register",
  "context": {
    "deviceId": "A123456789",
    "connRole": "task",
    "lastAckMsgId": "T123456",    // 客户端上一次确认收到的任务
    "curStepId": "step-2"         // 当前正在执行哪个步骤
  }
}
```

# 断线重连与任务状态恢复设计

## 设计思路

- ✨ 不重复发任务（防止同一 消息 被执行两次）
- 📦 设备重连后能自动续接任务（不中断任务流程）
- ✅ 弱网/瞬断环境下任务不丢、不重



## 服务端要求

### **1️⃣** **注册连接时恢复上次任务状态**

当设备重连，client 自动发送一条注册/同步消息，server 查询之前状态，判断是否有“未完成任务”。

### **2️⃣****任务执行状态持久化在 Redis**

你已有 Redis Hash / List 存储 msgId → stepId → status，这就是你的断线续接凭据。

**客户端断线前执行到了哪里、是否已上报都记录在 Redis 中。**

### **3️⃣** **任务发送时先加锁，不重复发**

- 每个 stepId 设置执行状态（running/success/failed）
- 避免重复发送
- 若 device 断线后 30s 重连，只要状态未完成，就自动补发（但不是重新创建）

## **客户端要求：**

### **1️⃣** **记录“上一个已完成任务 stepId / msgId”**

在本地持久层、内存中保存 lastAckMsgId 和 curRunningStepId。

重连时立刻发送 register + 状态同步 消息，协助服务端判断是否续接。



### **2️⃣** **接收到重复 stepId 的任务时判断是否已处理过**

可以通过 local stepIdSet.contains("step-2") 判断是否已经执行过，避免重复操作。

客户端应当对接收到的任务具备**幂等性检查能力**。



## 完整流程图

```java
[Server]                  [Device Client]
   |                           |
   | ---- 任务下发 step-2 --> |
   |                           | ← 弱网掉线 (未收到响应)
   |     x 等待 response       |
   |                           |
   | <--- 设备重新连接 ------- |
   |                           |
   | <-- 发送 register(lastAck=T123, step=2)
   |                           |
   | 查询 Redis 状态: step-2 = RUNNING (未完成)
   | --> 重发 step-2 ----------> |
   |                           |
   | <---- response(step-2 OK) |
   | Redis 标记 SUCCESS        |
```



# 任务执行的状态一致性

## 需求背景

在弱网或异常场景下，如果消息被判定“已丢失”，但后续客户端仍**上报了成功结果** ——

**希望仍认为任务是成功的（只要业务结果成功）**。

**以“业务结果唯一、状态最终一致”为前提，允许消息投递层“重复、补发、延迟”，但不造成逻辑错乱或状态污染。**

## **任务状态机 + 结果锁定**

### **每个 msgId 有一个明确的状态流：**

```java
INIT → SENT → RUNNING → SUCCESS | FAILED | EXPIRED
```

### **响应处理逻辑：**

| **场景**                                         | **响应处理**                               |
| ------------------------------------------------ | ------------------------------------------ |
| **msgId** 执行成功，当前状态为 INIT/SENT/RUNNING | ✅ 接受并更新为 SUCCESS                     |
| **msgId** 已标记为 SUCCESS                       | ✅ 忽略（幂等）                             |
| **msgId** 已标记为 FAILED                        | ✅ 接受成功上报，状态修正为 SUCCESS（容错） |
| **msgId** 已标记为 EXPIRED                       | ✅ 可接受成功结果（视业务容忍度）           |
| 新 **msgId** 的结果上报，旧任务未完成            | ✅ 用 redis 原子锁定谁先成功，其他忽略      |
| 同一个 **msgId** 多个重复结果                    | ✅ 幂等忽略，记录一次                       |

## 状态流转图示意：

```java
[SENT]
   ↓（执行成功）
[RUNNING]
   ↓（回调成功）
[SUCCESS] ←←←←←←←←←←←←←←←←←←←
   ↑                                ↑
   ↑（回调失败）                    ↑（重试结果）
[FAILED] ——→ EXPIRED                ↑
              ↓                    ↑
         后续仍上报 → 修正为 SUCCESS
```



## 总结

| **场景**                                  | **策略**                                     |
| ----------------------------------------- | -------------------------------------------- |
| SUCCESS 后有重复回调                      | 幂等忽略                                     |
| FAILED 后来有 SUCCESS                     | ✅ 自动修正                                   |
| EXPIRED 后来有 SUCCESS                    | ✅ 可配置是否接收                             |
| 新任务 msgid 尚未执行完，但旧任务结果成功 | ✅ 记录并锁定成功，忽略新任务结果（业务优先） |

| **等级**           | **状态**                 | **含义**                       | **可否修改**           |
| ------------------ | ------------------------ | ------------------------------ | ---------------------- |
| 🔁 中间态           | INIT, SENT, RUNNING      | 任务发出但未完成               | ✅ 可修改               |
| 🟡 最终态（失败类） | FAILED, EXPIRED, TIMEOUT | 本次任务未成功，但不是业务否定 | ✅ **可修正为 SUCCESS** |
| 🟢 最终态（成功类） | SUCCESS                  | 任务业务已成功                 | ❌ **不可修改**（锁定） |