# 任务调度系统架构

## 概述

任务调度系统是xa_mass_platform的核心业务引擎，负责管理任务的生命周期、设备分配、Token调度和消息执行。

## 核心对象

### 1. Task（任务）
- **职责**: 管理"业务级"生命周期和状态，是发起点
- **核心字段**: 
  - `tid`: 任务唯一ID
  - `taskName`: 任务名称
  - `project`: 所属project/app
  - `status`: 状态（NEW/READY/RUNNING/BLOCKED/TERMINAL）
  - `taskCountry`: 区域/国家
  - `taskInitNumber`: 总消息数
  - `taskValidNumber`: 有效消息数
  - `taskExecutedNumber`: 已完成消息数
  - `taskUnExecutedNumber`: 剩余消息数
  - `textContent`: 任务内容/模版
  - `user`: 所属用户/操作者

### 2. Device（设备）
- **职责**: 仅负责维护自身物理/网络/版本等属性
- **核心字段**:
  - `deviceId`: 唯一标识
  - `status`: 状态（ONLINE/OFFLINE/EXPIRED）
  - `agentVersion`: 插件/Agent版本
  - `lastHeartbeat`: 最后心跳时间
  - `supportedApps`: 支持的project/app列表
  - `groupId`: 分组信息
  - `lockExpireTime`: 当前分配锁过期时间

### 3. Token（号码/授权资源）
- **职责**: 负责授权与分配，是调度核心桥梁
- **核心字段**:
  - `tokenId`: 唯一标识
  - `deviceId`: 所属设备
  - `status`: 状态（LOGIN_READY/BIND_READY/SENDING/BLOCKED/INVALID）
  - `channel`: 通道/归属地
  - `lastBindTaskId`: 最近分配的任务ID
  - `expireTime`: 有效期/锁定期

### 4. TaskMsg（任务消息）
- **职责**: 记录单条任务的下发与执行过程
- **核心字段**:
  - `msgId`: 消息唯一标识
  - `taskId`: 所属任务
  - `deviceId`: 目标设备
  - `tokenId`: 使用token
  - `status`: 状态（INIT/BINDING/SENT/RUNNING/SUCCESS/FAILED/EXPIRED）
  - `batchId`: 批次信息
  - `sendTime`: 发送时间
  - `result`: 最终回执/响应码

## 状态流转

### Task状态流转
```
NEW → READY → RUNNING → TERMINAL
  ↓      ↓        ↓
BLOCKED ← ← ← ← ← ←
```

### Token状态流转
```
LOGIN_READY → BIND_READY → SENDING → LOGIN_READY
     ↓           ↓           ↓
   BLOCKED ← ← ← ← ← ← ← ← ← ←
     ↓
   INVALID (最终状态)
```

### TaskMsg状态流转
```
INIT → BINDING → SENT → RUNNING → SUCCESS
  ↓       ↓       ↓        ↓
FAILED ← ← ← ← ← ← ← ← ← ← ←
  ↓
EXPIRED
```

## 调度流程

### 1. 任务发起 → 分配 → 执行全流程

1. **任务创建/审核**
   - 管理员/用户新建Task，填入目标信息、发送策略
   - Task状态为NEW
   - 审核通过后，状态变为READY

2. **设备 & Token筛选**
   - 调用DeviceSelector按project/国家/能力/网络等过滤Device
   - 对每台Device调用TokenAllocator，找出可分配的空闲Token（LOGIN_READY）
   - 满足条件则进入batch binding，分配给任务

3. **批次绑定与下发**
   - 对已选中的Device+Token生成一批TaskMsg（batch）
   - TaskMsg状态为BINDING，批次确认后进入SENT

4. **消息下发与执行**
   - TaskMsg进入SENT，通过gateway/Netty推送到客户端
   - 设备接收后，进入RUNNING，真正执行任务

5. **完成/异常处理**
   - 设备完成回调，TaskMsg状态变为SUCCESS或FAILED
   - 设备失联/超时，TaskMsg状态变为EXPIRED
   - 对应的Token、Device状态恢复空闲或标记异常
   - Task统计已完成/剩余/失败等数值，自动切换到TERMINAL

## 核心接口

### 1. DeviceSelector
```java
public interface DeviceSelector {
    List<Device> selectDevices(Task task, List<Device> availableDevices, int requiredCount);
    boolean isDeviceSuitable(Device device, Task task);
    double getDevicePriority(Device device, Task task);
}
```

### 2. TokenAllocator
```java
public interface TokenAllocator {
    Token allocateToken(Device device, Task task, List<Token> availableTokens);
    Map<Device, Token> allocateTokens(Map<Device, List<Token>> deviceTokenMap, Task task);
    boolean isTokenSuitable(Token token, Task task);
    double getTokenPriority(Token token, Task task);
    boolean releaseToken(Token token);
}
```

### 3. TaskScheduler
```java
public interface TaskScheduler {
    SchedulingResult scheduleTask(Task task);
    boolean handleTaskMsgCompletion(TaskMsg taskMsg);
    boolean handleTaskMsgFailure(TaskMsg taskMsg, String errorMessage);
    boolean retryTaskMsg(TaskMsg taskMsg);
    boolean cancelTask(String taskId);
    boolean pauseTask(String taskId);
    boolean resumeTask(String taskId);
}
```

## 管理器类

### TaskManager
- 负责任务的CRUD操作和状态管理
- 提供任务审核、暂停、恢复、取消等功能
- 管理任务消息和统计信息

### TaskDeviceRuleManager
- 管理任务与设备的匹配规则
- 支持自定义规则配置

## 设计原则

1. **职责分离**: 每个对象只负责自己的核心职责
2. **状态机约束**: 所有状态转换都有明确的规则和约束
3. **可扩展性**: 通过接口和策略模式支持不同的调度算法
4. **可观测性**: 完整的状态跟踪和统计信息
5. **容错性**: 支持失败重试和异常恢复

## 使用示例

```java
// 创建任务
Task task = new Task("task001", "测试任务", "whatsapp", "US", 100, "Hello World", user);
taskManager.createTask(task);

// 审核任务
taskManager.approveTask("task001");

// 调度任务
TaskScheduler.SchedulingResult result = taskScheduler.scheduleTask(task);
if (result.isSuccess()) {
    System.out.println("调度成功，共调度 " + result.getScheduledCount() + " 条消息");
}

// 处理完成回调
TaskMsg taskMsg = new TaskMsg("msg001", "task001", "device001", "token001", "batch001");
taskMsg.markAsSuccess("OK");
taskScheduler.handleTaskMsgCompletion(taskMsg);
```

## 扩展点

1. **自定义设备选择策略**: 实现DeviceSelector接口
2. **自定义Token分配策略**: 实现TokenAllocator接口
3. **自定义调度算法**: 实现TaskScheduler接口
4. **自定义状态转换规则**: 修改状态枚举的canTransitionTo方法
5. **自定义重试策略**: 在TaskMsg中配置重试参数 