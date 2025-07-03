# JSON-DSL 框架

一个基于 JSON-DSL 的通用 Java 对象生成框架，支持批量生成任意对象、递归嵌套、内置函数和类型注册表。

## 特性

- 🚀 **通用性**: 支持生成任意 Java 对象
- 📝 **DSL 驱动**: 使用简洁的 JSON-DSL 语法定义生成规则
- 🔄 **递归嵌套**: 支持复杂的嵌套对象和集合结构
- 🛠️ **内置函数**: 提供丰富的内置函数（随机选择、范围、UUID、时间等）
- 📋 **类型注册**: 支持类型别名注册，避免硬编码全类名
- �� **多级作用域变量**: 支持 `&.index`（当前作用域索引简写）和 `&Model.index`，自动递归查找父作用域
- ⏰ **时间支持**: 支持当前时间和时间范围随机生成
- 🔧 **可扩展**: 内置函数和类型注册表支持动态扩展

## 快速开始

### 1. 注册类型

```java
// 注册类型别名
TypeRegistry.register("Device", Device.class);
TypeRegistry.register("Task", Task.class);
```

### 2. 定义 DSL

```json
{
  "MODEL": "Device",
  "COUNT": 3,
  "FIELDS": {
    "deviceId": {"$JOIN": ["device-", "&.index"]},
    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
    "groupId": {"$CHOICE": ["us", "gb", "cn"]},
    "agentVersion": {"$JOIN": ["1.0.", "&.index"]}
  }
}
```

### 3. 生成数据

```java
String deviceDsl = "..."; // 上面的 JSON
List<Object> devices = JsonDslEngine.generate(deviceDsl);
devices.forEach(System.out::println);
```

## DSL 语法

### 核心关键字

| 关键字 | 类型 | 说明 |
|--------|------|------|
| `MODEL` | String | 指定要生成的模型类名（必需） |
| `FIELDS` | Object | 字段配置映射 |
| `COUNT` | Number | 生成数量（默认1） |
| `TYPE` | String | 集合类型（LIST/SET） |

### 内置函数

| 函数 | 语法 | 说明 | 示例 |
|------|------|------|------|
| `$CHOICE` | `{"$CHOICE": [选项列表]}` | 从列表中随机选择 | `{"$CHOICE": ["A", "B", "C"]}` |
| `$RANGE` | `{"$RANGE": [最小值, 最大值]}` | 生成指定范围内的随机数 | `{"$RANGE": [1, 100]}` |
| `$UUID` | `{"$UUID": true}` | 生成 UUID | `{"$UUID": true}` |
| `$RANDOM` | `{"$RANDOM": true}` | 生成随机整数 | `{"$RANDOM": true}` |
| `$JOIN` | `{"$JOIN": [字符串列表]}` | 字符串拼接 | `{"$JOIN": ["prefix-", "&.index", "-suffix"]}` |
| `$NOW` | `{"$NOW": "格式化字符串"}` | 获取当前时间 | `{"$NOW": "yyyy-MM-dd HH:mm:ss"}` |
| `$TIME_RANGE` | `{"$TIME_RANGE": [开始时间, 结束时间, 时间单位, 格式化字符串]}` | 在时间范围内随机生成时间 | `{"$TIME_RANGE": ["now-1d", "now+1d", "HOURS", "yyyy-MM-dd HH:mm:ss"]}` |

### 多级作用域变量与简写

- 以 `&` 开头的字符串（如 `&.index`、`&Device.index`）会自动在当前及父作用域递归查找
- `&.index` 表示"当前作用域的索引"，推荐优先使用
- `&Model.index` 精确指向指定作用域的索引
- 支持多层嵌套、父子作用域隔离
- 推荐所有索引、作用域变量都用 `&.index` 或 `&Model.index` 方式命名

#### 变量查找示例

```json
{
  "MODEL": "Device",
  "COUNT": 2,
  "FIELDS": {
    "deviceId": {"$JOIN": ["device-", "&.index"]},
    "tasks": {
      "TYPE": "LIST",
      "COUNT": 2,
      "MODEL": "Task",
      "FIELDS": {
        "taskName": {"$JOIN": ["Task-", "&.index", "-of-Device-", "&Device.index"]},
        "parentDeviceId": "&Device.deviceId"
      }
    }
  }
}
```
- `&.index`：查找当前作用域的 index（如 Task 作用域时为 Task 的 index，Device 作用域时为 Device 的 index）
- `&Device.index`：查找最近的 Device 作用域的 index
- `&Device.deviceId`：查找最近的 Device 作用域的 deviceId

### 时间函数示例

```json
{
  "MODEL": "Task",
  "COUNT": 3,
  "FIELDS": {
    "tid": {"$UUID": true},
    "taskName": {"$JOIN": ["TimeTask-", "&.index"]},
    "createdTime": {"$NOW": "yyyy-MM-dd HH:mm:ss"},
    "lastModified": {"$TIME_RANGE": ["now-2h", "now", "MINUTES"]}
  }
}
```

## 类型注册

### 注册类型别名

```java
// 使用类对象注册
TypeRegistry.register("Device", Device.class);
TypeRegistry.register("Task", Task.class);

// 使用全类名注册
TypeRegistry.register("RuleDefinition", "com.xa.mass.engine.rules.RuleDefinition");
```

### 使用全类名

如果类型未注册，可以直接使用全类名：

```json
{
  "MODEL": "com.xa.mass.base.model.Device",
  "FIELDS": {
    "deviceId": "device-001"
  }
}
```

## 错误处理

框架使用 `JsonDslException` 统一处理错误：

```java
try {
    List<Object> objects = JsonDslEngine.generate(dsl);
} catch (JsonDslException e) {
    System.err.println("DSL 生成失败: " + e.getMessage());
}
```

常见错误：
- DSL 缺少 `MODEL` 字段
- 类型未注册或无法加载
- 字段设置失败
- 不支持的内置函数
- 时间格式解析失败

## 扩展性

### 添加新的内置函数

1. 在 `BuiltinFunc` 枚举中添加新函数
2. 在 `BuiltinFunctions` 中实现函数逻辑
3. 在 `TemplateValueResolver` 中注册解析器

### 自定义类型解析

可以通过继承或组合的方式扩展类型解析逻辑。

## 最佳实践

1. **类型注册**: 优先使用类型注册表，避免硬编码全类名
2. **错误处理**: 总是包装在 try-catch 中处理异常
3. **性能考虑**: 大量数据生成时考虑分批处理
4. **DSL 复用**: 将常用的 DSL 片段提取为常量或配置文件
5. **测试数据**: 使用有意义的测试数据，便于调试和验证
6. **作用域变量**: 推荐优先用 `&.index`，如需跨层访问用 `&Model.index`，避免变量冲突
7. **时间函数**: 
   - 使用相对时间 `now-1d` 比绝对时间更灵活
   - 合理选择时间单位和范围
   - 注意时间格式的正确性

## 依赖

- Java 8+
- Gson (用于 JSON 解析)
- 无其他外部依赖

## 许可证

本项目遵循项目整体许可证。 