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

# JSON-DSL 标准框架文档

## 设计目标

新的 JSON-DSL 标准框架旨在提供：

1. **统一的结构规范** - 所有 DSL 使用相同的标准格式
2. **良好的扩展性** - 支持新的 DSL 类型和处理器
3. **强大的调试能力** - 详细的日志和错误追踪
4. **灵活的合并机制** - 支持多源 DSL 的优先级合并
5. **向后兼容性** - 支持旧格式的自动转换

## 标准结构

### JsonDslDefinition

```json
{
  "uniqueId": "user-generator-001",
  "type": "generate",
  "priority": 1,
  "description": "生成用户数据",
  "version": "1.0",
  "createTime": 1640995200000,
  "updateTime": 1640995200000,
  "context": {
    "model": "com.xa.mass.base.model.User",
    "count": 10
  },
  "fieldDsl": {
    "name": "$RANDOM_NAME",
    "age": "$RANDOM_INT(18, 65)",
    "email": "$RANDOM_EMAIL"
  },
  "combineDsl": {
    "logic": "AND",
    "conditions": [
      "age > 18",
      "email.contains('@')"
    ]
  },
  "extensions": {
    "customField": "customValue"
  },
  "tags": ["user", "generator"],
  "author": "system",
  "enabled": true,
  "cacheable": false,
  "cacheExpireSeconds": 300
}
```

### 核心字段说明

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| uniqueId | String | 是 | DSL 唯一标识符 |
| type | String | 是 | DSL 类型：generate/filter/transform/validate |
| priority | Integer | 否 | 优先级，数字越小优先级越高 |
| description | String | 否 | DSL 描述信息 |
| version | String | 否 | 版本号，默认 "1.0" |
| context | Object | 否 | 上下文配置 |
| fieldDsl | Object | 否 | 字段 DSL 配置 |
| combineDsl | Object | 否 | 组合规则配置 |
| extensions | Object | 否 | 扩展配置 |
| tags | Array | 否 | 标签列表 |
| author | String | 否 | 作者信息 |
| enabled | Boolean | 否 | 是否启用，默认 true |
| cacheable | Boolean | 否 | 是否缓存，默认 false |
| cacheExpireSeconds | Integer | 否 | 缓存过期时间（秒） |

### DSL 类型

#### 1. Generate（生成）
用于生成对象实例

```json
{
  "uniqueId": "user-generator",
  "type": "generate",
  "context": {
    "model": "com.xa.mass.base.model.User",
    "count": 5
  },
  "fieldDsl": {
    "name": "$RANDOM_NAME",
    "age": "$RANDOM_INT(18, 65)",
    "email": "$RANDOM_EMAIL"
  }
}
```

#### 2. Filter（过滤）
用于过滤对象列表

```json
{
  "uniqueId": "age-filter",
  "type": "filter",
  "fieldDsl": {
    "age": "$EXPR(age > 30)",
    "status": "$EXPR(status == 'active')"
  },
  "combineDsl": {
    "logic": "AND"
  }
}
```

#### 3. Transform（转换）
用于转换对象格式

```json
{
  "uniqueId": "user-transform",
  "type": "transform",
  "fieldDsl": {
    "fullName": "$EXPR(firstName + ' ' + lastName)",
    "ageGroup": "$EXPR(age < 30 ? 'young' : age < 50 ? 'middle' : 'senior')"
  }
}
```

#### 4. Validate（校验）
用于校验对象有效性

```json
{
  "uniqueId": "user-validate",
  "type": "validate",
  "fieldDsl": {
    "email": "$EXPR(email.matches('^[^@]+@[^@]+\\.[^@]+$'))",
    "age": "$EXPR(age >= 0 && age <= 150)"
  }
}
```

## 表达式引擎

### 支持的表达式类型

1. **内置函数**：`$RANDOM_NAME`, `$RANDOM_INT(1, 100)`, `$RANDOM_EMAIL`
2. **QLExpress 表达式**：`$EXPR(age > 30 && status == 'active')`
3. **自定义函数**：通过扩展点注册

### 表达式语法

```json
{
  "fieldDsl": {
    "simpleField": "$RANDOM_NAME",
    "complexField": "$EXPR(age > 30 && (status == 'active' || status == 'pending'))",
    "calculatedField": "$EXPR(firstName + ' ' + lastName)"
  }
}
```

## 合并和优先级

### 合并策略

- **高优先级覆盖低优先级**：相同字段，高优先级 DSL 覆盖低优先级
- **字段级合并**：不同字段进行合并
- **冲突检测**：检测并报告合并冲突

### 示例

```java
// 低优先级 DSL
JsonDslDefinition lowPriority = new JsonDslDefinition("low", DslType.FILTER);
lowPriority.setPriority(10);
lowPriority.setFieldDsl(Map.of("age", "$EXPR(age > 20)"));

// 高优先级 DSL
JsonDslDefinition highPriority = new JsonDslDefinition("high", DslType.FILTER);
highPriority.setPriority(1);
highPriority.setFieldDsl(Map.of("age", "$EXPR(age > 30)"));

// 合并结果：age 字段使用高优先级的条件 (age > 30)
JsonDslDefinition merged = JsonDslMerger.merge(Arrays.asList(lowPriority, highPriority));
```

## 冲突检测

### 冲突类型

1. **字段冲突**：相同字段的不同规则
2. **逻辑冲突**：相互矛盾的组合逻辑
3. **类型冲突**：不兼容的 DSL 类型

### 冲突处理

```java
MergeResult result = JsonDslMerger.mergeWithConflictDetection(dslList);
if (result.hasConflicts()) {
    System.out.println("检测到冲突：");
    result.getConflicts().forEach(System.out::println);
}
```

## 扩展点

### 1. 表达式引擎扩展

```java
public interface ExpressionEngine {
    Object evaluate(String expression, Map<String, Object> context);
    boolean supports(String expression);
}

// 注册自定义表达式引擎
ExpressionEngineRegistry.register("custom", new CustomExpressionEngine());
```

### 2. 内置函数扩展

```java
public interface BuiltinFunction {
    Object execute(Object... args);
    String getName();
}

// 注册自定义函数
BuiltinFunctionRegistry.register(new CustomFunction());
```

### 3. 类型注册扩展

```java
// 注册自定义类型
TypeRegistry.register("CustomType", CustomType.class);
```

### 4. 合并策略扩展

```java
public interface MergeStrategy {
    JsonDslDefinition merge(List<JsonDslDefinition> dslList);
}

// 注册自定义合并策略
MergeStrategyRegistry.register("custom", new CustomMergeStrategy());
```

### 5. 扩展字段

```json
{
  "extensions": {
    "customProcessor": "CustomProcessorClass",
    "customConfig": {
      "key": "value"
    }
  }
}
```

### 6. 上下文参数

```java
ProcessingContext context = new ProcessingContext();
context.setParameter("customParam", "value");
context.setVariable("customVar", "value");
```

### 7. 缓存扩展

```json
{
  "cacheable": true,
  "cacheExpireSeconds": 600,
  "cacheKey": "custom-cache-key"
}
```

## 与旧 DSL 的差异

### 主要改进

1. **统一结构**：所有 DSL 使用相同的标准格式
2. **类型安全**：明确的 DSL 类型定义
3. **优先级支持**：内置优先级机制
4. **扩展性**：丰富的扩展点
5. **调试支持**：详细的日志和错误信息
6. **向后兼容**：支持旧格式自动转换

### 迁移指南

```java
// 旧格式
String oldFormat = "{ \"name\": \"$RANDOM_NAME\", \"age\": \"$RANDOM_INT(18, 65)\" }";

// 新格式
JsonDslDefinition newFormat = JsonDslParser.parse(oldFormat);
// 或者手动创建
JsonDslDefinition dsl = new JsonDslDefinition("user-generator", DslType.GENERATE);
dsl.setFieldDsl(Map.of(
    "name", "$RANDOM_NAME",
    "age", "$RANDOM_INT(18, 65)"
));
```

## 常见使用示例

### 1. 基本生成

```java
JsonDslDefinition dsl = new JsonDslDefinition("basic-generator", DslType.GENERATE);
dsl.setContext(new JsonDslContext("com.example.User", 5));
dsl.setFieldDsl(Map.of(
    "name", "$RANDOM_NAME",
    "age", "$RANDOM_INT(18, 65)"
));

List<Object> result = JsonDslEngine.generateList(JsonDslParser.toLegacyFormat(dsl));
```

### 2. 复杂过滤

```java
JsonDslDefinition dsl = new JsonDslDefinition("complex-filter", DslType.FILTER);
dsl.setFieldDsl(Map.of(
    "age", "$EXPR(age > 30)",
    "status", "$EXPR(status == 'active')"
));
dsl.setCombineDsl(Map.of("logic", "AND"));

String filterConfig = JsonDslParser.toLegacyFormat(dsl);
List<Object> filtered = JsonDslEngine.filter(objects, filterConfig);
```

### 3. 多源合并

```java
List<JsonDslDefinition> dslList = Arrays.asList(
    createUserDsl(),
    createAdminDsl(),
    createGuestDsl()
);

JsonDslDefinition merged = JsonDslMerger.merge(dslList);
```

## 调试和故障排除

### 启用调试模式

```java
ProcessingContext context = new ProcessingContext();
context.setDebug(true);
Object result = JsonDslProcessorEngine.process(dsl, context);
```

### 日志输出

```
[JsonDslProcessor] 开始处理 DSL: user-generator
[GenerateProcessor] 生成完成，数量: 5
[FilterProcessor] 过滤完成，原始数量: 5, 过滤后数量: 3
```

### 常见错误

1. **DSL 格式错误**：检查 JSON 格式和必填字段
2. **类型不匹配**：确保 DSL 类型与处理器匹配
3. **表达式错误**：检查 QLExpress 语法
4. **优先级冲突**：检查优先级设置

## 安全考虑

### 表达式安全

1. **沙箱环境**：QLExpress 在受限环境中执行
2. **函数白名单**：只允许预定义的函数
3. **资源限制**：限制执行时间和内存使用

### 输入验证

1. **DSL 验证**：使用 `validate()` 方法验证 DSL
2. **类型检查**：确保输入类型正确
3. **大小限制**：限制 DSL 大小和复杂度

---

## 新的处理器架构

### 设计原则

新的处理器架构遵循以下设计原则：

1. **单一职责原则**：每个处理器只负责一种 DSL 类型的处理
2. **开闭原则**：对扩展开放，对修改封闭
3. **依赖倒置原则**：依赖抽象接口而非具体实现
4. **链式调用**：支持多个 DSL 的链式处理

### 核心组件

#### 1. JsonDslProcessor 接口

```java
public interface JsonDslProcessor {
    Object process(JsonDslDefinition definition, ProcessingContext context);
    boolean supports(JsonDslDefinition.DslType type);
    String getName();
    int getPriority();
}
```

#### 2. ProcessingContext 上下文

```java
public class ProcessingContext {
    // 参数传递
    public void setParameter(String key, Object value);
    public Object getParameter(String key);
    
    // 变量存储
    public void setVariable(String key, Object value);
    public Object getVariable(String key);
    
    // 调试模式
    public void setDebug(boolean debug);
    public boolean isDebug();
    
    // 作用域管理
    public ProcessingContext createChild(String childScopeName);
}
```

#### 3. 处理器实现

- **GenerateProcessor**：处理 generate 类型 DSL
- **FilterProcessor**：处理 filter 类型 DSL  
- **TransformProcessor**：处理 transform 类型 DSL
- **ValidateProcessor**：处理 validate 类型 DSL

#### 4. ProcessorRegistry 注册表

```java
public class ProcessorRegistry {
    // 注册处理器
    public static void register(JsonDslProcessor processor);
    
    // 获取处理器
    public static JsonDslProcessor getProcessor(JsonDslDefinition.DslType type);
    
    // 链式处理
    public static Object processChain(List<JsonDslDefinition> definitions, ProcessingContext context);
}
```

#### 5. JsonDslProcessorEngine 引擎

```java
public class JsonDslProcessorEngine {
    // 处理单个 DSL
    public static <T> List<T> process(JsonDslDefinition definition, ProcessingContext context, Class<T> targetType);
    
    // 链式处理
    public static <T> List<T> processChain(List<JsonDslDefinition> definitions, ProcessingContext context, Class<T> targetType);
    
    // 从 JSON 处理
    public static <T> List<T> processFromJson(String jsonDsl, ProcessingContext context, Class<T> targetType);
    
    // 处理器管理
    public static void registerProcessor(JsonDslProcessor processor);
    public static List<JsonDslProcessor> getAllProcessors();
}
```

### 使用示例

#### 1. 单个 DSL 处理

```java
// 创建 DSL 定义
JsonDslDefinition dsl = new JsonDslDefinition("user-generator", DslType.GENERATE);
dsl.setContext(new JsonDslContext("com.example.User", 3));
dsl.setFieldDsl(Map.of("name", "$RANDOM_NAME", "age", "$RANDOM_INT(18, 65)"));

// 处理 DSL
Object result = JsonDslProcessorEngine.process(dsl);
```

#### 2. 链式处理

```java
// 创建处理上下文
ProcessingContext context = new ProcessingContext("chain-example");

// 创建 DSL 链
List<JsonDslDefinition> dslChain = Arrays.asList(
    createGenerateDsl(),
    createFilterDsl(),
    createTransformDsl()
);

// 链式处理
Object result = JsonDslProcessorEngine.processChain(dslChain, context);
```

#### 3. 自定义处理器

```java
// 实现自定义处理器
public class CustomProcessor implements JsonDslProcessor {
    @Override
    public Object process(JsonDslDefinition definition, ProcessingContext context) {
        // 自定义处理逻辑
        return customResult;
    }
    
    @Override
    public boolean supports(JsonDslDefinition.DslType type) {
        return DslType.TRANSFORM.equals(type);
    }
    
    @Override
    public String getName() {
        return "CustomProcessor";
    }
    
    @Override
    public int getPriority() {
        return 500;
    }
}

// 注册自定义处理器
JsonDslProcessorEngine.registerProcessor(new CustomProcessor());
```

#### 4. 调试模式

```java
// 创建调试上下文
ProcessingContext context = new ProcessingContext("debug-example");
context.setDebug(true);
context.setParameter("objects", Arrays.asList("test1", "test2", "test3"));

// 处理 DSL（会输出调试信息）
Object result = JsonDslProcessorEngine.process(dsl, context);
```

### 架构优势

1. **解耦合**：DSL 定义与处理逻辑分离
2. **可扩展**：易于添加新的处理器类型
3. **可测试**：每个处理器可以独立测试
4. **链式调用**：支持复杂的处理流程
5. **上下文传递**：处理器间可以共享状态
6. **调试友好**：详细的处理日志和错误信息

### 迁移指南

从旧的 DSL 处理方式迁移到新的处理器架构：

```java
// 旧方式
String legacyFormat = "{ \"name\": \"$RANDOM_NAME\" }";
List<Object> result = JsonDslEngine.generateList(legacyFormat);

// 新方式
JsonDslDefinition dsl = new JsonDslDefinition("generator", DslType.GENERATE);
dsl.setFieldDsl(Map.of("name", "$RANDOM_NAME"));
Object result = JsonDslProcessorEngine.process(dsl);
```

新的处理器架构提供了更好的设计、更强的扩展性和更清晰的代码结构，是 DSL 框架的重要改进。

### 6. 强类型处理器接口与异常风格（2024年7月更新）

#### 方法泛型接口

新版所有强类型处理器接口（GenerateProcessor、FilterProcessor、TransformProcessor、ValidateProcessor）均采用"方法泛型"，不再使用类泛型。例如：

```java
public interface GenerateProcessor extends JsonDslProcessor {
    <T> List<T> generate(JsonDslDefinition definition, ProcessingContext context, Class<T> targetType);
}

public interface FilterProcessor extends JsonDslProcessor {
    <T> List<T> filter(List<T> input, JsonDslDefinition definition, ProcessingContext context);
}

public interface TransformProcessor extends JsonDslProcessor {
    <T> T transform(T input, JsonDslDefinition definition, ProcessingContext context);
}

public interface ValidateProcessor extends JsonDslProcessor {
    <T> List<String> validate(T input, JsonDslDefinition definition, ProcessingContext context);
}
```

这样同一个处理器实例可处理任意类型对象，提升了复用性和灵活性。

#### 统一异常风格

所有 DSL 相关参数校验、处理错误均应抛出 `JsonDslException`，不再抛 `IllegalArgumentException`。如：

```java
if (definition == null) {
    throw new JsonDslException("Definition cannot be null");
}
```

#### 升级与兼容性

- 旧代码如有 `GenerateProcessor<T>`、`DefaultGenerateProcessor<T>` 等类泛型声明，需升级为无类泛型，方法签名用 `<T>`。
- 处理器注册、获取、链式调用等 API 不变。

#### 示例：强类型链式处理

```java
GenerateProcessor generateProcessor = new DefaultGenerateProcessor();
FilterProcessor filterProcessor = new DefaultFilterProcessor();

List<User> users = generateProcessor.generate(dsl, context, User.class);
List<User> filtered = filterProcessor.filter(users, filterDsl, context);
```

#### 示例：异常捕获

```java
try {
    generateProcessor.generate(null, context, User.class);
} catch (JsonDslException e) {
    // 处理参数校验异常
}
```

--- 