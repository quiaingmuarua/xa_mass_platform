# JsonDslEngine API 重构说明

## 概述

本次重构解决了 `JsonDslEngine.generate()` 方法返回值类型不够灵活的问题，并提供了类型安全的 API 设计。通过引入 `ReturnType` 枚举参数，用户可以明确控制返回类型，避免了类型转换的不确定性。

## 变更内容

### 1. 新增返回类型枚举

```java
public enum ReturnType {
    AUTO,    // 自动判断：单个对象返回 Object，多个对象返回 List，多个模型返回 Map
    SINGLE,  // 强制返回单个对象
    LIST,    // 强制返回对象列表（默认）
    MAP      // 强制返回模型映射
}
```

### 2. 主要方法变更

#### 默认方法（推荐）
```java
// 默认返回 List<Object>，类型安全且一致
public static List<Object> generate(String jsonDsl)
```

#### 指定返回类型方法
```java
// 通过参数控制返回类型
public static <T> T generate(String jsonDsl, ReturnType returnType)
```

### 3. 便利方法

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

### 4. 向后兼容

保留了旧的 `generateListOld` 方法（已废弃）：
```java
@Deprecated
public static List<Object> generateListOld(String jsonDsl)
```

## 设计优势

### 1. 类型安全
- 默认方法总是返回 `List<Object>`，类型明确
- 通过 `ReturnType` 参数明确控制返回类型
- 避免了 `Object` 类型转换的不确定性

### 2. 灵活性
- 支持四种返回类型：`AUTO`, `SINGLE`, `LIST`, `MAP`
- 可以根据实际需求选择合适的返回类型
- 提供了便利方法简化常见场景

### 3. 一致性
- 默认行为统一，减少混淆
- API 设计更加直观和可预测
- 支持外部判断逻辑

## 迁移指南

### 从旧 API 迁移到新 API

#### 旧代码
```java
List<Object> devices = JsonDslEngine.generate(deviceDsl);
devices.forEach(System.out::println);
```

#### 新代码（推荐）
```java
// 默认返回列表（推荐）
List<Object> devices = JsonDslEngine.generate(deviceDsl);
devices.forEach(System.out::println);

// 或者指定返回类型
List<Object> devices = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.LIST);
```

#### 如果需要单个对象
```java
// 使用便利方法
Object device = JsonDslEngine.generateSingle(deviceDsl);

// 或者指定返回类型
Object device = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.SINGLE);
```

### 常见使用场景

#### 1. 默认返回列表（推荐）
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

// 总是返回 List<Object>，即使只有一个对象
List<Object> devices = JsonDslEngine.generate(deviceDsl);
System.out.println("生成了 " + devices.size() + " 个设备");
```

#### 2. 指定返回单个对象
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

#### 3. 指定返回映射
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

#### 4. 自动判断返回类型
```java
// 根据 DSL 结构自动判断返回类型
Object result = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.AUTO);

if (result instanceof List) {
    List<?> devices = (List<?>) result;
    System.out.println("生成了 " + devices.size() + " 个设备");
} else if (result instanceof Map) {
    Map<?, ?> models = (Map<?, ?>) result;
    System.out.println("生成了 " + models.size() + " 个模型");
} else {
    System.out.println("生成了单个对象: " + result);
}
```

## 返回类型说明

| ReturnType | 说明 | 示例 |
|-----------|------|------|
| `LIST` (默认) | 总是返回 `List<Object>` | 单个对象包装为单元素列表 |
| `SINGLE` | 总是返回 `Object` | 多个对象时返回第一个 |
| `MAP` | 总是返回 `Map<String, Object>` | 单个对象包装为 `{"result": object}` |
| `AUTO` | 根据 DSL 结构自动判断 | 单个对象返回 Object，多个对象返回 List，多个模型返回 Map |

## 测试验证

运行 `SimpleTest` 类可以验证新 API 的功能：

```bash
mvn exec:java -Dexec.mainClass="com.xa.mass.base.jsondsl.SimpleTest" -Dexec.classpathScope=test
```

## 影响范围

### 已更新的文件
- `JsonDslEngine.java` - 核心 API 重构，添加 ReturnType 枚举
- `MonkeyGenerator.java` - 更新调用方式
- 所有测试文件 - 更新为新的 API 调用方式
- `README.md` - 更新文档和示例
- `SimpleTest.java` - 新的测试示例

### 需要手动更新的文件
如果您的代码中直接使用了 `JsonDslEngine.generate()` 方法，请按照迁移指南进行更新。

## 最佳实践

1. **推荐使用默认方法**: `JsonDslEngine.generate(dsl)` 总是返回列表，类型安全且一致
2. **明确指定返回类型**: 当需要特定类型时，使用 `ReturnType` 参数
3. **使用便利方法**: 对于常见场景，使用 `generateSingle()`, `generateList()`, `generateMap()`
4. **类型检查**: 使用 `instanceof` 检查返回类型，特别是在使用 `AUTO` 模式时
5. **外部判断**: 可以通过 `ReturnType.AUTO` 让外部代码根据 DSL 结构决定处理逻辑

## 注意事项

1. 新 API 默认返回 `List<Object>`，确保类型一致性
2. 使用 `ReturnType.SINGLE` 时，多个对象会返回第一个
3. 使用 `ReturnType.MAP` 时，单个对象会被包装为 `{"result": object}`
4. 旧 API 已废弃，建议尽快迁移到新 API
5. `ReturnType.AUTO` 提供了最大的灵活性，但需要类型检查 