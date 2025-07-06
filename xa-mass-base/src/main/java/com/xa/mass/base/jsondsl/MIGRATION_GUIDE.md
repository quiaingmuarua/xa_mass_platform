# JSON-DSL 迁移指南

## 概述

为了提供更好的类型安全、扩展性和维护性，JSON-DSL 框架已经升级到新的标准化结构。本指南将帮助您从旧的 DSL 系统迁移到新的标准化 DSL 系统。

## 过时的类和接口

以下类和接口已被标记为过时（`@Deprecated`），将在未来版本中移除：

### 核心类

| 旧类 | 新替代方案 | 说明 |
|------|------------|------|
| `JsonDslEngine` | `JsonDslDefinition` + `JsonDslParser` | 新的标准化 DSL 定义和解析 |
| `DslContext` | `JsonDslContext` | 更丰富的上下文配置 |
| `DslKeyword` | 标准化字段名 | 使用更清晰的字段命名 |
| `DslObjectBuilder` | `JsonDslParser` | 统一的 DSL 解析和构建 |
| `TemplateValueResolver` | 表达式引擎 | 支持多种表达式引擎 |
| `TypeRegistry` | 内置类型管理 | 更丰富的类型管理机制 |
| `BuiltinFunctions` | 表达式引擎 | 通过表达式引擎提供函数 |
| `BuiltinFunc` | 表达式引擎 | 通过表达式引擎提供函数 |
| `JsonDslException` | 标准化异常 | 更好的错误处理机制 |

## 迁移示例

### 1. 从 JsonDslEngine 迁移

**旧方式：**
```java
// 生成数据
String dsl = """
{
  "MODEL": "Device",
  "COUNT": 3,
  "FIELDS": {
    "deviceId": {"$JOIN": ["device-", "&.index"]},
    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]}
  }
}
""";

List<Device> devices = JsonDslEngine.generateList(dsl, Device.class);

// 过滤数据
List<Object> filtered = JsonDslEngine.filter(devices, "status", "eq", "ONLINE");
```

**新方式：**
```java
// 1. 创建标准化 DSL 定义
JsonDslDefinition definition = new JsonDslDefinition("device_generator", JsonDslDefinition.DslType.GENERATE);
definition.setDescription("生成设备数据");
definition.setAuthor("system");

// 2. 设置上下文
JsonDslContext context = new JsonDslContext("Device", 3);
definition.setContext(context);

// 3. 设置字段 DSL
Map<String, Object> fieldDsl = Map.of(
    "deviceId", Map.of("$JOIN", List.of("device-", "&.index")),
    "status", Map.of("$CHOICE", List.of("ONLINE", "OFFLINE"))
);
definition.setFieldDsl(fieldDsl);

// 4. 解析并生成数据
String legacyFormat = JsonDslParser.toLegacyFormat(definition);
List<Device> devices = JsonDslEngine.generateList(legacyFormat, Device.class);

// 5. 创建过滤器 DSL
JsonDslDefinition filterDef = new JsonDslDefinition("status_filter", JsonDslDefinition.DslType.FILTER);
filterDef.setFieldDsl(Map.of("status", Map.of("eq", "ONLINE")));

// 6. 应用过滤
String filterConfig = JsonDslParser.toLegacyFormat(filterDef);
List<Object> filtered = JsonDslEngine.filter(devices, filterConfig);
```

### 2. 从 DslContext 迁移

**旧方式：**
```java
DslContext context = new DslContext();
context.setScopeName("Device");
context.setVariable("&Device.index", 0);
```

**新方式：**
```java
JsonDslContext context = new JsonDslContext("Device", 1);
context.setScopeName("Device");
context.setParameter("index", 0);
context.setDebug(true);
context.setStrict(false);
```

### 3. 从内置函数迁移

**旧方式：**
```json
{
  "MODEL": "Device",
  "FIELDS": {
    "deviceId": {"$JOIN": ["device-", "&.index"]},
    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
    "createdTime": {"$NOW": "yyyy-MM-dd HH:mm:ss"}
  }
}
```

**新方式：**
```json
{
  "unique_id": "device_generator",
  "type": "generate",
  "context": {
    "MODEL": "Device",
    "COUNT": 1
  },
  "fieldDsl": {
    "deviceId": {"$JOIN": ["device-", "&.index"]},
    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
    "createdTime": {
      "$EXPR": {
        "lang": "ql",
        "expr": "now('yyyy-MM-dd HH:mm:ss')"
      }
    }
  }
}
```

### 4. 从 TypeRegistry 迁移

**旧方式：**
```java
TypeRegistry.register("Device", Device.class);
TypeRegistry.register("Task", Task.class);
```

**新方式：**
```java
// 新标准支持直接使用全类名，无需注册
// 或者通过 context 中的 model 字段指定
JsonDslContext context = new JsonDslContext("com.xa.mass.base.model.Device", 1);
```

## 新标准优势

### 1. 类型安全
- 强类型定义和验证
- 编译时错误检查
- 更好的 IDE 支持

### 2. 扩展性
- 支持多种表达式引擎
- 可插拔的规则引擎
- 丰富的扩展点

### 3. 可维护性
- 统一的 DSL 结构
- 清晰的元数据管理
- 版本控制和兼容性

### 4. 调试体验
- 唯一标识符追踪
- 详细的错误信息
- 调试模式支持

## 迁移时间表

| 版本 | 状态 | 说明 |
|------|------|------|
| 2.0.0 | 当前 | 标记过时，提供迁移指南 |
| 2.1.0 | 计划 | 增强新标准功能 |
| 3.0.0 | 计划 | 移除过时类和方法 |

## 兼容性说明

- 旧版本 DSL 格式仍然支持（通过 `JsonDslParser.parseLegacyDsl`）
- 新标准提供向后兼容的转换方法
- 建议逐步迁移，避免一次性大规模改动

## 获取帮助

如果在迁移过程中遇到问题，请：

1. 查看 `STANDARD_DSL_DESIGN.md` 了解新标准设计
2. 参考 `JsonDslMergerExample.java` 了解合并功能
3. 查看 `FilterExample.java` 了解过滤器使用
4. 提交 Issue 或联系开发团队

## 总结

新的标准化 DSL 系统提供了更好的类型安全、扩展性和维护性。虽然迁移需要一些工作，但长期来看将大大提升开发体验和系统稳定性。建议按照本指南逐步迁移，确保平滑过渡。 