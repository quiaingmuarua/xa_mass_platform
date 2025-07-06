# 新版 JSON-DSL 标准文档

## 目录
1. [设计目标](#设计目标)
2. [标准结构体](#标准结构体)
3. [核心字段说明](#核心字段说明)
4. [类型与用法](#类型与用法)
5. [表达式与引擎](#表达式与引擎)
6. [多来源合并与优先级](#多来源合并与优先级)
7. [冲突检测与调试](#冲突检测与调试)
8. [可扩展点](#可扩展点)
9. [与旧版区别](#与旧版区别)
10. [常见用法示例](#常见用法示例)
11. [调试与安全建议](#调试与安全建议)

---

## 1. 设计目标
- **结构化**：统一 DSL 结构，便于解析、扩展和排查。
- **可扩展**：支持多种类型（生成、过滤、转换、校验等），可插拔表达式/规则引擎。
- **优先级合并**：多来源规则合并时高优先级覆盖低优先级。
- **兼容性**：兼容旧版 DSL，支持自动转换。
- **调试友好**：唯一 ID、元数据、调试模式、冲突检测。

---

## 2. 标准结构体

```json
{
  "unique_id": "string",         // 唯一标识
  "type": "generate|filter|transform|validate", // DSL 类型
  "priority": 10,                 // 优先级，数值越大优先级越高
  "desc": "描述信息",
  "version": "1.0",
  "author": "作者",
  "tags": ["tag1", "tag2"],
  "enabled": true,                // 是否启用
  "context": { ... },             // 上下文配置，见下
  "fieldDsl": { ... },            // 字段规则
  "combine_dsl": { ... },         // 组合规则（多字段/复杂表达式）
  "extensions": { ... },          // 扩展信息
  "cacheable": false,             // 是否可缓存
  "cache_expire_seconds": 300     // 缓存过期时间
}
```

### context 字段结构
```json
{
  "MODEL": "Device",           // 模型类名或注册别名
  "COUNT": 3,                   // 生成数量，默认 1
  "TYPE": "LIST",              // 集合类型：LIST/SET/MAP
  "scope_name": "Device",      // 作用域名称
  "parent_scope": "Parent",    // 父作用域引用
  "parameters": { ... },        // 额外参数
  "debug": false,               // 调试模式
  "strict": true                // 严格模式
}
```

---

## 3. 核心字段说明
- **unique_id**：每条 DSL 的唯一标识，便于追踪和调试。
- **type**：DSL 类型，支持 generate（生成）、filter（过滤）、transform（转换）、validate（校验）。
- **priority**：合并时的优先级，数值越大优先级越高。
- **context**：上下文配置，决定生成/过滤的模型、数量、作用域等。
- **fieldDsl**：字段级规则，支持内置函数、表达式、嵌套对象、集合等。
- **combine_dsl**：多字段联合判断、复杂业务逻辑。
- **extensions**：自定义扩展信息，便于业务扩展。
- **enabled/cacheable**：可控开关与缓存策略。

---

## 4. 类型与用法
- **generate**：批量生成对象，支持递归嵌套、内置函数、表达式。
- **filter**：对象过滤，支持字段条件、表达式、组合规则。
- **transform**：对象转换，支持字段映射、表达式、批量处理。
- **validate**：对象校验，支持必填、正则、枚举、复杂校验表达式。

---

## 5. 表达式与引擎
- **$EXPR** 字段支持多种表达式引擎（如 QLExpress、SpEL、自定义）：
```json
{
  "$EXPR": {
    "lang": "ql", // 表达式引擎类型
    "expr": "status == 'OFFLINE' ? 0 : range(10, 100)"
  }
}
```
- 也可直接用字符串，默认 QLExpress：
```json
{"$EXPR": "status == 'OFFLINE' ? 0 : range(10, 100)"}
```
- **可插拔机制**：实现 `ExpressionEngine` 接口并注册到 `ExpressionEngineRegistry`，即可扩展新引擎。

---

## 6. 多来源合并与优先级
- 支持多来源（如 project、user、task）规则合并。
- 合并策略：高优先级覆盖低优先级，同字段/规则优先级高的生效。
- 提供多种合并模式（覆盖、合并、交集、并集），可定制。
- 冲突检测：可输出冲突字段、来源、优先级等详细信息。

---

## 7. 冲突检测与调试
- 每次合并可调用冲突检测方法，输出冲突详情。
- 支持调试模式（context.debug=true），详细输出合并、解析、执行过程。
- 唯一 ID、优先级、来源追踪，便于定位问题。

---

## 8. 可扩展点
- **表达式引擎**：实现 `ExpressionEngine` 并注册。
- **内置函数**：扩展 `BuiltinFunc` 和 `BuiltinFunctions`，或通过表达式引擎扩展。
- **类型注册**：通过 `TypeRegistry.register` 注册新模型类型。
- **合并策略**：自定义合并逻辑、冲突处理。
- **扩展字段**：通过 `extensions` 字段扩展业务元数据。
- **上下文参数**：context.parameters 支持任意业务参数。
- **缓存与开关**：支持自定义缓存策略、启用/禁用控制。

---

## 9. 与旧版区别
- 结构更标准化，字段更清晰，支持元数据和扩展。
- 支持多类型 DSL（生成/过滤/转换/校验），表达能力更强。
- 合并与优先级机制更完善，支持冲突检测。
- 可插拔表达式/规则引擎，兼容多种业务场景。
- 兼容旧版 DSL，自动转换。

---

## 10. 常见用法示例

### 生成设备数据
```json
{
  "unique_id": "device_gen_001",
  "type": "generate",
  "context": {"MODEL": "Device", "COUNT": 2},
  "fieldDsl": {
    "deviceId": {"$JOIN": ["device-", "&.index"]},
    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
    "createdTime": {"$EXPR": {"lang": "ql", "expr": "now('yyyy-MM-dd HH:mm:ss')"}}
  }
}
```

### 过滤在线设备
```json
{
  "unique_id": "online_filter",
  "type": "filter",
  "fieldDsl": {
    "status": {"eq": "ONLINE"},
    "groupId": {"in": ["us", "gb"]}
  },
  "combine_dsl": {
    "battery_check": "batteryLevel >= 20"
  }
}
```

### 复杂嵌套与表达式
```json
{
  "unique_id": "complex_gen",
  "type": "generate",
  "context": {"MODEL": "Device", "COUNT": 1},
  "fieldDsl": {
    "deviceId": {"$JOIN": ["device-", "&.index"]},
    "tasks": {
      "TYPE": "LIST",
      "COUNT": 2,
      "MODEL": "Task",
      "FIELDS": {
        "tid": {"$UUID": true},
        "taskName": {"$JOIN": ["Task-", "&.index", "-of-Device-", "&Device.index"]}
      }
    },
    "onlineStrategy": {"$EXPR": "status == 'OFFLINE' ? 0 : range(10, 100)"}
  }
}
```

---

## 11. 调试与安全建议
- **调试模式**：建议开发/排查时开启 context.debug=true，输出详细日志。
- **唯一 ID**：每条 DSL 建议分配唯一 ID，便于追踪。
- **表达式安全**：自定义表达式引擎时注意注入风险，做好沙箱隔离。
- **缓存策略**：合理设置 cacheable 和 cache_expire_seconds，避免缓存脏数据。
- **兼容性**：如需兼容旧 DSL，可用 `JsonDslParser.parseLegacyDsl` 自动转换。

---

如需更多示例和扩展用法，请参考 `NewStandardDslExample.java`、`JsonDslMergerExample.java` 等示例文件。 