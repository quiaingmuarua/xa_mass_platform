# 规则引擎重构说明

## 概述

本次重构将 `EngineExample` 中的硬编码设备匹配逻辑替换为基于 QLExpress 的规则引擎，提供了更灵活、可配置的设备匹配策略。

## 主要改进

### 1. 新增组件

#### DeviceMatchContext

- **位置**: `com.xa.mass.engine.model.DeviceMatchContext`
- **作用**: 创建设备匹配上下文，为规则引擎提供评估所需的所有属性
- **包含属性**:
    - 设备状态、版本、支持的应用等
    - Token 状态、通道等
    - 任务属性
    - 计算属性（如国家匹配、应用支持等）

#### RuleConfig

- **位置**: `com.xa.mass.engine.rules.RuleConfig`
- **作用**: 管理不同类型的设备匹配规则配置
- **提供方法**:
    - `getDefaultDeviceMatchRules()`: 默认规则集
    - `getAdvancedDeviceMatchRules()`: 高级规则集
    - `getProjectSpecificRules(String projectName)`: 项目特定规则
    - `getLooseDeviceMatchRules()`: 宽松规则（用于测试）

### 2. 规则定义

#### 默认规则集包含以下规则：

1. **基础设备状态检查** (`basic_device_check`)
   ```ql
   isDeviceAvailable == true && isDeviceLocked == false
   ```

2. **Token状态检查** (`token_status_check`)
   ```ql
   isTokenAllocatable == true && isTokenAvailable == true
   ```

3. **国家/地区匹配** (`country_match`)
   ```ql
   countryMatch == true || channelMatch == true
   ```

4. **应用支持检查** (`app_support_check`)
   ```ql
   supportsProject == true
   ```

5. **设备负载检查** (`device_load_check`)
   ```ql
   appCount < 10
   ```

### 3. 使用方法

#### 基本使用

```java
// 使用默认规则
RuleManager<Map<String, Object>> ruleManager = initRuleManager();

// 使用项目特定规则
RuleManager<Map<String, Object>> ruleManager = initRuleManagerWithProjectRules("demoApp");

// 使用宽松规则（测试用）
RuleManager<Map<String, Object>> ruleManager = initRuleManagerWithLooseRules();
```

#### 命令行参数

```bash
# 使用默认规则
java EngineExample

# 使用宽松规则
java EngineExample loose

# 使用项目特定规则
java EngineExample project
```

### 4. 规则引擎优势

#### 灵活性

- 规则可以通过配置文件或数据库动态加载
- 支持复杂的条件组合
- 可以针对不同项目定制不同规则

#### 可维护性

- 规则逻辑与业务代码分离
- 规则变更无需修改代码
- 支持规则的版本管理

#### 可扩展性

- 支持多种规则类型（QLExpress、JSON DSL等）
- 可以添加新的规则评估器
- 支持规则的优先级和权重

### 5. 上下文属性说明

规则引擎可以访问以下上下文属性：

#### 设备属性

- `deviceId`: 设备ID
- `deviceStatus`: 设备状态
- `deviceGroupId`: 设备分组ID
- `agentVersion`: Agent版本
- `supportedApps`: 支持的应用列表
- `isDeviceAvailable`: 设备是否可用
- `isDeviceLocked`: 设备是否被锁定
- `appCount`: 支持的应用数量

#### Token属性

- `tokenId`: Token ID
- `tokenStatus`: Token状态
- `tokenChannel`: Token通道
- `isTokenAllocatable`: Token是否可分配
- `isTokenAvailable`: Token是否可用

#### 任务属性

- `taskId`: 任务ID
- `taskName`: 任务名称
- `taskProject`: 任务项目
- `taskCountry`: 任务国家
- `taskStatus`: 任务状态
- `taskInitNumber`: 初始消息数
- `batchSize`: 批次大小
- `runTaskMinDeviceCnt`: 最小设备数

#### 计算属性

- `supportsProject`: 是否支持项目
- `countryMatch`: 国家是否匹配
- `channelMatch`: 通道是否匹配

### 6. 示例规则

#### 复杂规则示例

```ql
// 设备必须在线、未锁定、支持项目、Token可用、且应用数量在合理范围内
isDeviceAvailable == true && 
isDeviceLocked == false && 
supportsProject == true && 
isTokenAllocatable == true && 
appCount >= 1 && appCount <= 5 &&
agentVersion.startsWith('1.')
```

#### 项目特定规则示例

```ql
// demoApp项目的特殊要求
supportsProject == true && 
appCount <= 5 && 
agentVersion.startsWith('1.0') &&
countryMatch == true
```

### 7. 性能考虑

- 规则评估是同步执行的，对于大量设备可能需要优化
- 可以考虑缓存规则评估结果
- 对于复杂规则，建议使用索引优化设备查询

### 8. 扩展建议

1. **规则持久化**: 将规则存储到数据库，支持动态配置
2. **规则版本管理**: 支持规则的版本控制和回滚
3. **规则性能监控**: 添加规则执行时间统计
4. **规则调试工具**: 提供规则调试和测试工具
5. **规则模板**: 提供常用规则的模板库

## 总结

通过引入规则引擎，设备匹配逻辑变得更加灵活和可维护。开发人员可以通过配置规则来调整匹配策略，而无需修改代码。这大大提高了系统的可扩展性和可维护性。 
