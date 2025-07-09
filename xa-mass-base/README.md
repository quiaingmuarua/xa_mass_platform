# XA-Mass Base Platform

XA-Mass 基础平台，提供 JSON-DSL 框架和核心功能组件。

## 核心功能

### JSON-DSL 框架

一个强大的 JSON 驱动的领域特定语言框架，支持数据生成、过滤、转换和验证。

#### 主要特性

- **类型安全**：强类型处理器，编译时检查
- **扩展性强**：支持自定义处理器和函数
- **统一接口**：标准化的处理器接口
- **智能过滤**：支持单对象和列表的智能过滤
- **链式处理**：支持多个 DSL 的链式执行

#### DSL 类型

1. **GENERATE** - 数据生成
2. **FILTER** - 数据过滤  
3. **TRANSFORM** - 数据转换
4. **VALIDATE** - 数据验证

## FilterProcessor 重构说明

### 重构背景

原始的 `FilterProcessor` 接口存在以下问题：
- 强制要求 `List<T>` 输入，限制了单对象过滤场景
- 泛型类型混乱，`T` 可能是单个对象也可能是列表
- 接口设计不够灵活，无法适应不同的使用场景

### 重构方案

采用**明确区分单对象和列表**的设计方案：

```java
public interface FilterProcessor extends JsonDslProcessor {
    
    /**
     * 过滤单个对象
     * 
     * @param data 要过滤的单个对象
     * @param definition DSL 定义
     * @param context 处理上下文
     * @return 过滤结果
     */
    <T> FilterResult<T> filter(T data, JsonDslDefinition definition, ProcessingContext context);
    
    /**
     * 过滤对象列表
     * 
     * @param dataList 要过滤的对象列表
     * @param definition DSL 定义
     * @param context 处理上下文
     * @return 过滤结果
     */
    <T> FilterResult<T> filterList(List<T> dataList, JsonDslDefinition definition, ProcessingContext context);
}
```

### 设计优势

1. **类型安全**：明确区分 `T` 和 `List<T>`，避免泛型擦除问题
2. **接口清晰**：单对象和列表分别处理，职责明确
3. **向后兼容**：保持 `FilterResult<T>` 返回类型，现有代码无需修改
4. **扩展性强**：支持任何类型的对象过滤

### 使用示例

#### 单个对象过滤

```java
// 创建过滤条件
JsonDslDefinition filterDef = new JsonDslDefinition("age-filter", JsonDslDefinition.DslType.FILTER);
Map<String, Object> fieldDsl = new HashMap<>();
fieldDsl.put("age", Map.of("$EXPR", "age > 20"));
filterDef.setFieldDsl(fieldDsl);

// 过滤单个用户
User user = new User("Alice", 25, "active");
FilterProcessor processor = new DefaultFilterProcessor();
FilterResult<User> result = processor.filter(user, filterDef, context);

if (!result.getPassed().isEmpty()) {
    System.out.println("用户通过过滤");
} else {
    System.out.println("用户未通过过滤");
}
```

#### 列表对象过滤

```java
// 过滤用户列表
List<User> users = Arrays.asList(
    new User("Alice", 25, "active"),
    new User("Bob", 15, "active"),
    new User("Charlie", 35, "active")
);

FilterResult<User> result = processor.filterList(users, filterDef, context);

System.out.println("通过过滤的用户数量: " + result.getPassed().size());
System.out.println("未通过过滤的用户数量: " + result.getFailed().size());

// 获取通过的用户
for (User user : result.getPassed()) {
    System.out.println("通过: " + user.getName());
}
```

#### Map 对象过滤

```java
// 过滤 Map 对象
Map<String, Object> userMap = new HashMap<>();
userMap.put("name", "Eve");
userMap.put("age", 30);
userMap.put("status", "active");

FilterResult<Map<String, Object>> result = processor.filter(userMap, filterDef, context);
```

### 批量过滤便利方法

在 `JsonDslProcessorEngine` 中提供了便利的批量过滤方法：

```java
// 简单批量过滤
List<User> filtered = JsonDslProcessorEngine.filterBatch(users, filterDef, context, User.class);

// 带详细信息的批量过滤
FilterResult<User> result = JsonDslProcessorEngine.filterBatchWithDetails(users, filterDef, context, User.class);
```

### 泛型类型安全

重构解决了泛型擦除问题：

```java
// ✅ 正确：T = User，返回 FilterResult<User>
FilterResult<User> result1 = processor.filter(user, def, ctx);

// ✅ 正确：T = User，返回 FilterResult<User>  
FilterResult<User> result2 = processor.filterList(userList, def, ctx);

// ✅ 正确：T = Map<String, Object>，返回 FilterResult<Map<String, Object>>
FilterResult<Map<String, Object>> result3 = processor.filter(userMap, def, ctx);
```

### 错误处理

- **不支持的类型**：抛出 `JsonDslException`
- **转换失败**：在 `FilterResult` 中记录失败原因
- **参数验证**：使用统一的 `ParameterValidator` 进行参数校验

### 性能优化

- **批量处理**：列表过滤时复用单对象过滤逻辑
- **调试模式**：支持详细的调试日志输出
- **内存优化**：避免不必要的对象创建

## 开发指南

### 添加自定义处理器

```java
@Component
public class CustomFilterProcessor implements FilterProcessor {
    
    @Override
    public <T> FilterResult<T> filter(T data, JsonDslDefinition definition, ProcessingContext context) {
        // 实现单对象过滤逻辑
    }
    
    @Override
    public <T> FilterResult<T> filterList(List<T> dataList, JsonDslDefinition definition, ProcessingContext context) {
        // 实现列表过滤逻辑
    }
    
    @Override
    public boolean supports(JsonDslDefinition.DslType type) {
        return JsonDslDefinition.DslType.FILTER.equals(type);
    }
}
```

### 注册处理器

```java
// 自动注册（推荐）
@Component
public class CustomFilterProcessor implements FilterProcessor {
    // 实现...
}

// 手动注册
JsonDslProcessorEngine.registerProcessor(new CustomFilterProcessor());
```

## 测试

运行测试验证功能：

```bash
# 运行所有测试
mvn test

# 运行特定测试
mvn test -Dtest=DefaultFilterProcessorTest
mvn test -Dtest=GenericTypeTest
```

## 版本历史

### v1.0.0
- 初始版本，支持基本的 JSON-DSL 功能

### v1.1.0  
- 重构 FilterProcessor 接口，解决泛型类型安全问题
- 明确区分单对象和列表过滤
- 提供批量过滤便利方法
- 增强错误处理和调试功能

## 贡献指南

1. Fork 项目
2. 创建功能分支
3. 提交更改
4. 推送到分支
5. 创建 Pull Request

## 许可证

MIT License 