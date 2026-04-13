# 标准化 JSON-DSL 设计文档

## 设计理念

### 问题背景

原有的 JSON-DSL 结构过于简单，缺乏：

- 统一的标识和追踪机制
- 类型区分和优先级控制
- 元数据管理和文档化支持
- 扩展性和兼容性保障
- 调试和问题排查能力

### 设计目标

1. **标准化结构** - 定义明确的结构体，便于理解和维护
2. **向后兼容** - 支持传统 DSL 格式，平滑迁移
3. **可扩展性** - 预留扩展字段，支持未来功能
4. **可追踪性** - 提供唯一标识和元数据，便于调试
5. **类型安全** - 通过类型枚举确保 DSL 类型正确性

## 核心结构

### 标准化 DSL 格式

```json
{
  "unique_id": "dsl_identifier",
  "type": "generate|filter|transform|validate",
  "priority": 1,
  "desc": "DSL 描述信息",
  "version": "1.0",
  "create_time": 1640995200000,
  "update_time": 1640995200000,
  "context": {
    "MODEL": "class_name",
    "COUNT": 1,
    "TYPE": "LIST|SET|MAP",
    "scope_name": "scope_name",
    "parent_scope": "parent_scope",
    "parameters": {},
    "debug": false,
    "strict": false
  },
  "fieldDsl": {
    "field_name": "field_value_or_function"
  },
  "combine_dsl": {
    "rule_id": "rule_expression"
  },
  "extensions": {
    "extension_key": "extension_value"
  },
  "tags": [
    "tag1",
    "tag2"
  ],
  "author": "author_name",
  "enabled": true,
  "cacheable": false,
  "cache_expire_seconds": 300
}
```

### 字段说明

#### 核心字段

| 字段          | 类型      | 必需 | 说明                                        |
|-------------|---------|----|-------------------------------------------|
| `unique_id` | String  | 是  | DSL 唯一标识符，用于调试和缓存                         |
| `type`      | String  | 是  | DSL 类型：generate/filter/transform/validate |
| `priority`  | Integer | 否  | 执行优先级，数字越小优先级越高                           |
| `desc`      | String  | 否  | DSL 描述信息，用于文档化                            |
| `version`   | String  | 否  | 版本号，用于兼容性控制                               |

#### 时间字段

| 字段            | 类型   | 说明      |
|---------------|------|---------|
| `create_time` | Long | 创建时间戳   |
| `update_time` | Long | 最后修改时间戳 |

#### 配置字段

| 字段            | 类型     | 说明                     |
|---------------|--------|------------------------|
| `context`     | Object | 上下文配置，包含 MODEL、COUNT 等 |
| `fieldDsl`    | Object | 字段 DSL 配置，定义各字段生成规则    |
| `combine_dsl` | Object | 组合规则配置，支持多字段联合判断       |
| `extensions`  | Object | 扩展配置，用于未来功能扩展          |

#### 元数据字段

| 字段                     | 类型      | 说明               |
|------------------------|---------|------------------|
| `tags`                 | Array   | 标签列表，用于分类和筛选     |
| `author`               | String  | 作者信息             |
| `enabled`              | Boolean | 是否启用，默认为 true    |
| `cacheable`            | Boolean | 是否缓存结果，默认为 false |
| `cache_expire_seconds` | Integer | 缓存过期时间（秒）        |

## DSL 类型

### 1. GENERATE（生成）

用于生成对象实例

```json
{
  "unique_id": "device_generator",
  "type": "generate",
  "context": {
    "MODEL": "Device",
    "COUNT": 3
  },
  "fieldDsl": {
    "deviceId": {
      "$JOIN": [
        "device-",
        "&.index"
      ]
    },
    "status": {
      "$CHOICE": [
        "ONLINE",
        "OFFLINE"
      ]
    }
  }
}
```

### 2. FILTER（过滤）

用于过滤对象列表

```json
{
  "unique_id": "online_device_filter",
  "type": "filter",
  "fieldDsl": {
    "status": {
      "eq": "ONLINE"
    },
    "groupId": {
      "in": [
        "us",
        "gb"
      ]
    }
  }
}
```

### 3. TRANSFORM（转换）

用于转换对象结构

```json
{
  "unique_id": "device_transformer",
  "type": "transform",
  "fieldDsl": {
    "deviceId": {"$UPPER": "&.deviceId"},
    "status": {"$MAP": {"ONLINE": "active", "OFFLINE": "inactive"}}
  }
}
```

### 4. VALIDATE（验证）

用于验证对象有效性

```json
{
  "unique_id": "device_validator",
  "type": "validate",
  "fieldDsl": {
    "deviceId": {"required": true, "pattern": "^device-\\d+$"},
    "status": {"enum": ["ONLINE", "OFFLINE"]}
  }
}
```

## 上下文配置

### context 字段详解

```json
{
  "context": {
    "MODEL": "Device",           // 模型类名或注册别名
    "COUNT": 3,                  // 生成数量，默认 1
    "TYPE": "LIST",              // 集合类型：LIST/SET/MAP
    "scope_name": "Device",      // 作用域名称
    "parent_scope": "Parent",    // 父作用域引用
    "parameters": {              // 额外参数
      "env": "dev",
      "region": "us"
    },
    "debug": false,              // 调试模式
    "strict": true               // 严格模式
  }
}
```

## 组合规则

### combine_dsl 字段详解

支持多字段联合判断和复杂业务逻辑：

```json
{
  "combine_dsl": {
    "status_group_rule": "status == 'ONLINE' ? groupId : 'unknown'",
    "version_check_rule": "agentVersion.startsWith('1.0') ? 'stable' : 'beta'",
    "capacity_rule": "groupId == 'us' ? 100 : groupId == 'gb' ? 50 : 30"
  }
}
```

## 向后兼容

### 传统 DSL 格式支持

系统自动识别传统格式并转换为标准化格式：

**传统格式：**

```json
{
  "MODEL": "Device",
  "COUNT": 3,
  "FIELDS": {
    "deviceId": {"$JOIN": ["device-", "&.index"]},
    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]}
  }
}
```

**自动转换为：**

```json
{
  "unique_id": "legacy_1640995200000",
  "type": "generate",
  "desc": "从传统 DSL 结构转换",
  "version": "1.0",
  "context": {
    "MODEL": "Device",
    "COUNT": 3
  },
  "fieldDsl": {
    "deviceId": {
      "$JOIN": [
        "device-",
        "&.index"
      ]
    },
    "status": {
      "$CHOICE": [
        "ONLINE",
        "OFFLINE"
      ]
    }
  }
}
```

## 使用示例

### 1. 创建标准化 DSL

```java
// 创建 DSL 定义
JsonDslDefinition definition = new JsonDslDefinition("my_device_generator", JsonDslDefinition.DslType.GENERATE);
definition.

setDescription("生成测试设备数据");
definition.

setAuthor("test_user");
definition.

setTags(new String[] {
    "device", "test"
});

// 设置上下文
JsonDslContext context = new JsonDslContext("Device", 3);
context.

setScopeName("Device");
definition.

setContext(context);

// 设置字段 DSL
definition.

setFieldDsl(Map.of(
        "deviceId", Map.of("$JOIN", List.of("device-", "&.index")),
        "status",Map.

of("$CHOICE",List.of("ONLINE", "OFFLINE"))
        ));

// 验证
        definition.

validate();
```

### 2. 解析 DSL

```java
// 解析标准化 DSL
String jsonDsl = "..."; // 标准化 DSL JSON
JsonDslDefinition definition = JsonDslParser.parse(jsonDsl);

// 转换为传统格式并生成数据
String legacyFormat = JsonDslParser.toJson(definition);
List<Device> devices = JsonDslEngine.generateList(legacyFormat, Device.class);
```

### 3. 向后兼容使用

```java
// 直接使用传统格式（自动转换）
String legacyDsl = """
                {
                  "MODEL": "Device",
                  "COUNT": 3,
                  "FIELDS": {
                    "deviceId": {"$JOIN": ["device-", "&.index"]}
                  }
                }
                """;

List<Device> devices = JsonDslEngine.generateList(legacyDsl, Device.class);
```

## 扩展机制

### 1. 扩展字段

通过 `extensions` 字段支持未来功能扩展：

```json
{
  "extensions": {
    "business_rules": {
      "max_devices": 100,
      "preferred_groups": ["us", "gb"]
    },
    "performance": {
      "cache_strategy": "lru",
      "batch_size": 50
    }
  }
}
```

### 2. 自定义 DSL 类型

通过扩展 `DslType` 枚举支持新的 DSL 类型：

```java
public enum DslType {
    GENERATE("generate", "对象生成"),
    FILTER("filter", "对象过滤"),
    TRANSFORM("transform", "对象转换"),
    VALIDATE("validate", "对象验证"),
    CUSTOM("custom", "自定义类型"); // 新增类型
}
```

## 最佳实践

### 1. 命名规范

- `unique_id`: 使用有意义的标识符，如 `device_generator_v1`
- `desc`: 提供清晰的描述信息
- `tags`: 使用一致的标签体系

### 2. 版本管理

- 使用语义化版本号
- 在 `desc` 中记录变更信息
- 通过 `version` 字段控制兼容性

### 3. 缓存策略

- 对于频繁使用的 DSL，设置 `cacheable: true`
- 根据数据更新频率设置合适的 `cache_expire_seconds`
- 在调试时设置 `debug: true`

### 4. 错误处理

- 使用 `validate()` 方法验证 DSL 定义
- 通过 `unique_id` 追踪问题
- 利用 `desc` 和 `tags` 提供上下文信息

## 迁移指南

### 从传统格式迁移

1. **渐进式迁移**：系统支持传统格式，可以逐步迁移
2. **自动转换**：使用 `JsonDslParser.parse()` 自动转换
3. **手动优化**：根据业务需求添加元数据和扩展字段

### 迁移步骤

1. 为现有 DSL 添加 `unique_id` 和 `type` 字段
2. 将 `FIELDS` 重命名为 `fieldDsl`
3. 将 `MODEL`、`COUNT` 等移到 `context` 中
4. 添加描述性字段（`desc`、`author`、`tags`）
5. 根据需要添加扩展字段

## 总结

标准化 DSL 结构提供了：

- **更好的可维护性**：明确的结构和元数据
- **更强的扩展性**：预留扩展字段和类型系统
- **更高的可追踪性**：唯一标识和调试信息
- **完整的兼容性**：向后兼容传统格式
- **丰富的功能**：支持多种 DSL 类型和组合规则

这个设计既保持了原有 DSL 的简洁性，又提供了企业级应用所需的标准化和可扩展性。 