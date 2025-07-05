# JSON-DSL 框架

一个基于 JSON-DSL 的通用 Java 对象生成框架，支持批量生成任意对象、递归嵌套、内置函数和类型注册表。

## 特性

- 🚀 **通用性**: 支持生成任意 Java 对象
- 📝 **DSL 驱动**: 使用简洁的 JSON-DSL 语法定义生成规则
- 🔄 **递归嵌套**: 支持复杂的嵌套对象和集合结构
- 🛠️ **内置函数**: 提供丰富的内置函数（随机选择、范围、UUID、时间等）
- 📋 **类型注册**: 支持类型别名注册，避免硬编码全类名
- 🎯 **类型安全**: 通过参数控制返回类型，提供明确的类型保证
- 🔧 **多级作用域变量**: 支持 `&.index`（当前作用域索引简写）和 `&Model.index`，自动递归查找父作用域
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

// 默认返回列表（推荐）
List<Object> devices = JsonDslEngine.generate(deviceDsl);
devices.forEach(System.out::println);

// 指定返回类型
Object singleDevice = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.SINGLE);
List<Object> deviceList = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.LIST);
Map<String, Object> deviceMap = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.MAP);
```

## API 设计

### 返回类型枚举

```java
public enum ReturnType {
    AUTO,    // 自动判断：单个对象返回 Object，多个对象返回 List，多个模型返回 Map
    SINGLE,  // 强制返回单个对象
    LIST,    // 强制返回对象列表（默认）
    MAP      // 强制返回模型映射
}
```

### 核心方法

#### `generate(String jsonDsl)` - 默认返回列表
```java
// 默认返回 List<Object>，即使 DSL 只定义了一个对象也会包装为列表
List<Object> devices = JsonDslEngine.generate(deviceDsl);
```

#### `generate(String jsonDsl, ReturnType returnType)` - 指定返回类型
```java
// 返回单个对象
Object device = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.SINGLE);

// 返回列表（与默认方法相同）
List<Object> devices = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.LIST);

// 返回映射
Map<String, Object> models = JsonDslEngine.generate(modelsDsl, JsonDslEngine.ReturnType.MAP);

// 自动判断（根据 DSL 结构）
Object result = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.AUTO);
```

### 便利方法

#### `generateSingle(String jsonDsl)`
强制返回单个对象：
```java
Object device = JsonDslEngine.generateSingle(deviceDsl);
```

#### `generateList(String jsonDsl)`
强制返回对象列表：
```java
List<Object> devices = JsonDslEngine.generateList(deviceDsl);
```

#### `generateMap(String jsonDsl, String modelName)`
强制返回模型映射：
```java
Map<String, Object> models = JsonDslEngine.generateMap(deviceDsl, "Device");
```

#### `generateTyped(String jsonDsl, Class<T> targetType)`
带类型转换的生成方法：
```java
List<Object> devices = JsonDslEngine.generateTyped(deviceDsl, List.class);
Map<String, Object> models = JsonDslEngine.generateTyped(modelsDsl, Map.class);
```

## 使用示例

### 示例1：默认返回列表（推荐）

```java
String singleDeviceDsl = """
{
    "MODEL": "Device",
    "FIELDS": {
        "deviceId": "device-001",
        "status": "ONLINE"
    }
}
""";

// 默认返回 List<Object>，即使只有一个对象
List<Object> devices = JsonDslEngine.generate(singleDeviceDsl);
System.out.println("生成了 " + devices.size() + " 个设备");
```

### 示例2：指定返回单个对象

```java
String deviceDsl = """
{
    "MODEL": "Device",
    "COUNT": 3,
    "FIELDS": {
        "deviceId": {"$JOIN": ["device-", "&.index"]},
        "status": {"$CHOICE": ["ONLINE", "OFFLINE"]}
    }
}
""";

// 返回第一个对象
Object device = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.SINGLE);
System.out.println("第一个设备: " + device);
```

### 示例3：指定返回列表

```java
String singleDsl = """
{
    "MODEL": "Device",
    "FIELDS": {
        "deviceId": "device-001",
        "status": "ONLINE"
    }
}
""";

// 强制返回列表，单个对象会被包装
List<Object> devices = JsonDslEngine.generate(singleDsl, JsonDslEngine.ReturnType.LIST);
System.out.println("列表大小: " + devices.size()); // 输出: 1
```

### 示例4：指定返回映射

```java
String deviceDsl = """
{
    "MODEL": "Device",
    "FIELDS": {
        "deviceId": "device-001",
        "status": "ONLINE"
    }
}
""";

// 强制返回映射，单个对象会被包装
Map<String, Object> models = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.MAP);
System.out.println("映射键: " + models.keySet()); // 输出: [result]
```

### 示例5：多个模型

```java
String multipleModelsDsl = """
{
    "device": {
        "MODEL": "Device",
        "FIELDS": {
            "deviceId": "device-001",
            "status": "ONLINE"
        }
    },
    "task": {
        "MODEL": "Task",
        "FIELDS": {
            "taskId": "task-001",
            "priority": "HIGH"
        }
    }
}
""";

// 返回 Map<String, Object>
Map<String, Object> models = JsonDslEngine.generate(multipleModelsDsl, JsonDslEngine.ReturnType.MAP);
models.forEach((key, value) -> {
    System.out.println("模型 " + key + ": " + value.getClass().getSimpleName());
});
```

### 示例6：使用便利方法

```java
String deviceDsl = "..."; // 包含 COUNT: 2 的 DSL

// 强制获取单个对象
Object single = JsonDslEngine.generateSingle(deviceDsl);

// 强制获取列表
List<Object> list = JsonDslEngine.generateList(deviceDsl);

// 强制获取映射
Map<String, Object> map = JsonDslEngine.generateMap(deviceDsl, "Device");
```

## 返回类型说明

| ReturnType | 说明 | 示例 |
|-----------|------|------|
| `LIST` (默认) | 总是返回 `List<Object>` | 单个对象包装为单元素列表 |
| `SINGLE` | 总是返回 `Object` | 多个对象时返回第一个 |
| `MAP` | 总是返回 `Map<String, Object>` | 单个对象包装为 `{"result": object}` |
| `AUTO` | 根据 DSL 结构自动判断 | 单个对象返回 Object，多个对象返回 List，多个模型返回 Map |

## 最佳实践

1. **推荐使用默认方法**: `JsonDslEngine.generate(dsl)` 总是返回列表，类型安全且一致
2. **明确指定返回类型**: 当需要特定类型时，使用 `ReturnType` 参数
3. **使用便利方法**: 对于常见场景，使用 `generateSingle()`, `generateList()`, `generateMap()`
4. **类型检查**: 使用 `instanceof` 检查返回类型，特别是在使用 `AUTO` 模式时

## DSL 语法

### 核心关键字

| 关键字      | 类型     | 说明             |
|----------|--------|----------------|
| `MODEL`  | String | 指定要生成的模型类名（必需） |
| `FIELDS` | Object | 字段配置映射         |
| `COUNT`  | Number | 生成数量（默认1）      |
| `TYPE`   | String | 集合类型（LIST/SET） |

### 内置函数

| 函数            | 语法                                            | 说明           | 示例                                                                      |
|---------------|-----------------------------------------------|--------------|-------------------------------------------------------------------------|
| `$CHOICE`     | `{"$CHOICE": [选项列表]}`                         | 从列表中随机选择     | `{"$CHOICE": ["A", "B", "C"]}`                                          |
| `$RANGE`      | `{"$RANGE": [最小值, 最大值]}`                      | 生成指定范围内的随机数  | `{"$RANGE": [1, 100]}`                                                  |
| `$UUID`       | `{"$UUID": true}`                             | 生成 UUID      | `{"$UUID": true}`                                                       |
| `$RANDOM`     | `{"$RANDOM": true}`                           | 生成随机整数       | `{"$RANDOM": true}`                                                     |
| `$JOIN`       | `{"$JOIN": [字符串列表]}`                          | 字符串拼接        | `{"$JOIN": ["prefix-", "&.index", "-suffix"]}`                          |
| `$NOW`        | `{"$NOW": "格式化字符串"}`                          | 获取当前时间       | `{"$NOW": "yyyy-MM-dd HH:mm:ss"}`                                       |
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
TypeRegistry.register("Device",Device .class);
TypeRegistry.

register("Task",Task .class);

// 使用全类名注册
TypeRegistry.

register("RuleDefinition","com.xa.mass.engine.rules.RuleDefinition");
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
try{
List<Object> objects = JsonDslEngine.generate(dsl);
}catch(
JsonDslException e){
        System.err.

println("DSL 生成失败: "+e.getMessage());
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

## 表达式引擎与内置函数别名

### QLExpress 表达式支持

- 支持通过 `$EXPR` 字段嵌入 QLExpress 表达式，表达式可引用当前上下文变量和所有内置函数。
- 所有内置函数（如 random、choice、range、uuid、join、now、timeRange 等）均支持多种别名（如
  random/rand、timeRange/timerange），可在表达式中直接调用。
- 内置函数注册采用集中自动注册机制，所有别名和实现统一维护于 BuiltinFunc 和 BuiltinFunctions，无需手动注册。

#### 示例：动态 mock 字段依赖表达式

```json
{
  "MODEL": "Device",
  "FIELDS": {
    "status": {"$CHOICE": ["OFFLINE", "ONLINE"]},
    "onlineStrategy": {
      "$EXPR": {
        "lang": "ql",
        "expr": "status == 'OFFLINE' ? 0 : range(10, 100)"
      }
    },
    "randValue": {"$EXPR": {"lang": "ql", "expr": "random(1, 10)"}},
    "timeStr": {"$EXPR": {"lang": "ql", "expr": "now('yyyy-MM-dd HH:mm')"}},
    "timeRange1": {"$EXPR": {"lang": "ql", "expr": "timeRange('now-1d', 'now', 'HOURS', 'yyyy-MM-dd HH:mm')"}},
    "timeRange2": {"$EXPR": {"lang": "ql", "expr": "timerange('now-1d', 'now', 'HOURS', 'yyyy-MM-dd HH:mm')"}}
  }
}
```

- 支持表达式内任意组合内置函数、上下文变量、三元表达式等。
- 所有内置函数别名（如 random/rand、timeRange/timerange）均可直接在表达式中调用。
- 表达式变量自动注入，无需手动声明。

### 内置函数别名与自动注册机制

- BuiltinFunc 枚举支持为每个内置函数配置多个别名。
- BuiltinFunctions.registerToQLExpress 会自动遍历所有别名批量注册，无需手动维护注册代码。
- 新增内置函数时，只需在 BuiltinFunc 和 FUNCTION_MAP 中补充即可，注册和别名自动生效。

### $EXPR 语法糖支持

- 支持直接写字符串作为表达式，等价于 `{lang: 'ql', expr: ...}`，无需冗余对象包裹。
- 推荐写法：

```json
{
  "MODEL": "Device",
  "FIELDS": {
    "randValue": {"$EXPR": "random(1, 10)"},
    "status": {"$EXPR": "choice(['ONLINE','OFFLINE'])"}
  }
}
```

- 兼容原有对象写法：

```json
{
  "randValue": {"$EXPR": {"lang": "ql", "expr": "random(1, 10)"}}
}
```

- 绝大多数场景推荐直接用字符串写法，简洁直观。

## 依赖

- Java 8+
- Gson (用于 JSON 解析)
- 无其他外部依赖

## 许可证

本项目遵循项目整体许可证。 