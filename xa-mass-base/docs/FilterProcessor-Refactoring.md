# FilterProcessor 重构技术文档

## 概述

本文档详细说明了 `FilterProcessor` 接口的重构过程、技术决策和最佳实践，重点解决了 Java 泛型类型擦除问题，提供了类型安全的过滤解决方案。

## 问题分析

### 原始设计问题

1. **泛型类型混乱**
   ```java
   // 原始接口
   <T> FilterResult<T> filter(List<T> data, ...);
   
   // 问题：当 T = List<User> 时，实际类型变成 List<List<User>>
   // 但泛型擦除后，编译器无法区分
   ```

2. **使用场景限制**
   - 强制要求 `List<T>` 输入，无法处理单个对象
   - 单对象过滤需要包装成列表，增加复杂性

3. **类型安全问题**
   ```java
   // 编译时无法检测的类型错误
   List<User> users = Arrays.asList(user1, user2);
   FilterResult<List<User>> result = processor.filter(users, def, ctx);
   // 实际返回的是 FilterResult<User>，类型不匹配
   ```

## 重构方案

### 设计原则

1. **明确类型边界**：区分 `T` 和 `List<T>`
2. **保持向后兼容**：维持 `FilterResult<T>` 返回类型
3. **类型安全**：编译时检查，避免运行时类型错误
4. **接口清晰**：职责分离，易于理解和维护

### 新接口设计

```java
public interface FilterProcessor extends JsonDslProcessor {
    
    /**
     * 过滤单个对象
     * 
     * @param data 要过滤的单个对象
     * @param definition DSL 定义
     * @param context 处理上下文
     * @return 过滤结果，包含通过和失败的对象
     */
    <T> FilterResult<T> filter(T data, JsonDslDefinition definition, ProcessingContext context);
    
    /**
     * 过滤对象列表
     * 
     * @param dataList 要过滤的对象列表
     * @param definition DSL 定义
     * @param context 处理上下文
     * @return 过滤结果，包含通过和失败的对象
     */
    <T> FilterResult<T> filterList(List<T> dataList, JsonDslDefinition definition, ProcessingContext context);
}
```

### 实现策略

#### 1. 类型检测和分发

```java
@Override
public <T> FilterResult<T> filter(T data, JsonDslDefinition definition, ProcessingContext context) {
    // 参数验证
    ParameterValidator.validateNotNull(data, "data");
    ParameterValidator.validateNotNull(definition, "definition");
    ParameterValidator.validateNotNull(context, "context");
    
    // 类型检测和分发
    if (data instanceof List) {
        @SuppressWarnings("unchecked")
        List<T> dataList = (List<T>) data;
        return filterList(dataList, definition, context);
    } else if (data instanceof Map) {
        return filterMap((Map<String, Object>) data, definition, context);
    } else {
        return filterSingleObject(data, definition, context);
    }
}
```

#### 2. 私有方法实现

```java
/**
 * 过滤单个对象（私有方法）
 */
private <T> FilterResult<T> filterSingleObject(T data, JsonDslDefinition definition, ProcessingContext context) {
    // 实现单对象过滤逻辑
}

/**
 * 过滤列表对象（私有方法）
 */
private <T> FilterResult<T> filterList(List<T> dataList, JsonDslDefinition definition, ProcessingContext context) {
    // 实现列表过滤逻辑
}

/**
 * 过滤 Map 对象（私有方法）
 */
private FilterResult<Map<String, Object>> filterMap(Map<String, Object> data, JsonDslDefinition definition, ProcessingContext context) {
    // 实现 Map 过滤逻辑
}
```

## 泛型类型安全解决方案

### 问题根源

Java 泛型在运行时被擦除，导致以下问题：

```java
// 编译时错误：方法签名冲突
public <T> FilterResult<T> filter(T data, ...) { }
public <T> FilterResult<T> filter(List<T> data, ...) { }
// 擦除后都变成：filter(Object data, ...)
```

### 解决方案

1. **明确方法名区分**
   ```java
   filter(T data, ...)           // 单对象
   filterList(List<T> data, ...) // 列表
   ```

2. **类型安全的使用方式**
   ```java
   // ✅ 正确：明确的类型
   User user = new User();
   FilterResult<User> result1 = processor.filter(user, def, ctx);
   
   List<User> users = Arrays.asList(user);
   FilterResult<User> result2 = processor.filterList(users, def, ctx);
   ```

3. **编译时类型检查**
   ```java
   // ❌ 编译错误：类型不匹配
   FilterResult<List<User>> result = processor.filterList(users, def, ctx);
   // 正确：FilterResult<User>
   ```

## 性能优化

### 1. 批量处理优化

```java
private <T> FilterResult<T> filterList(List<T> dataList, JsonDslDefinition definition, ProcessingContext context) {
    List<T> passed = new ArrayList<>();
    List<FilterFailure<T>> failed = new ArrayList<>();
    
    // 批量处理，避免重复创建上下文
    for (T data : dataList) {
        try {
            if (evaluateFilter(data, definition, context)) {
                passed.add(data);
            } else {
                failed.add(new FilterFailure<>(data, "Filter condition not met"));
            }
        } catch (Exception e) {
            failed.add(new FilterFailure<>(data, e.getMessage()));
        }
    }
    
    return new FilterResult<>(passed, failed);
}
```

### 2. 表达式缓存

```java
// 缓存编译后的表达式，避免重复编译
private final Map<String, CompiledExpression> expressionCache = new ConcurrentHashMap<>();

private CompiledExpression getCompiledExpression(String expression) {
    return expressionCache.computeIfAbsent(expression, this::compileExpression);
}
```

### 3. 内存优化

```java
// 使用对象池减少 GC 压力
private final ObjectPool<FilterResult> resultPool = new ObjectPool<>();

private <T> FilterResult<T> createFilterResult() {
    return resultPool.borrow();
}
```

## 错误处理策略

### 1. 统一异常处理

```java
public class ParameterValidator {
    
    public static void validateNotNull(Object value, String paramName) {
        if (value == null) {
            throw new JsonDslException("Parameter '" + paramName + "' cannot be null");
        }
    }
    
    public static void validateDslType(JsonDslDefinition definition, JsonDslDefinition.DslType expectedType) {
        if (!expectedType.equals(definition.getDslType())) {
            throw new JsonDslException("Expected DSL type: " + expectedType + 
                                     ", but got: " + definition.getDslType());
        }
    }
}
```

### 2. 详细失败信息

```java
public class FilterFailure<T> {
    private final T data;
    private final String reason;
    private final String field;
    private final Object expectedValue;
    private final Object actualValue;
    
    // 构造函数和 getter 方法
}
```

### 3. 调试模式

```java
public class ProcessingContext {
    private boolean debugMode = false;
    private final List<String> debugLogs = new ArrayList<>();
    
    public void addDebugLog(String message) {
        if (debugMode) {
            debugLogs.add(message);
        }
    }
}
```

## 测试策略

### 1. 类型安全测试

```java
@Test
public void testGenericTypeSafety() {
    // 测试泛型类型正确性
    User user = new User("Alice", 25);
    FilterResult<User> result = processor.filter(user, def, ctx);
    
    // 验证返回类型
    assertThat(result.getPassed()).isInstanceOf(List.class);
    assertThat(result.getPassed().get(0)).isInstanceOf(User.class);
}
```

### 2. 边界条件测试

```java
@Test
public void testEdgeCases() {
    // 空列表
    FilterResult<User> result1 = processor.filterList(Collections.emptyList(), def, ctx);
    assertThat(result1.getPassed()).isEmpty();
    
    // null 值处理
    assertThatThrownBy(() -> processor.filter(null, def, ctx))
        .isInstanceOf(JsonDslException.class);
}
```

### 3. 性能测试

```java
@Test
public void testPerformance() {
    List<User> largeList = generateLargeUserList(10000);
    
    long startTime = System.currentTimeMillis();
    FilterResult<User> result = processor.filterList(largeList, def, ctx);
    long endTime = System.currentTimeMillis();
    
    assertThat(endTime - startTime).isLessThan(1000); // 1秒内完成
}
```

## 最佳实践

### 1. 接口设计

- **单一职责**：每个方法只负责一种类型的过滤
- **类型明确**：避免泛型类型混淆
- **向后兼容**：保持现有 API 的兼容性

### 2. 实现原则

- **防御性编程**：充分的参数验证和错误处理
- **性能考虑**：批量处理和缓存机制
- **可维护性**：清晰的代码结构和文档

### 3. 使用建议

- **明确类型**：使用时明确指定泛型类型
- **错误处理**：正确处理过滤失败的情况
- **性能优化**：大数据量时使用批量处理

## 迁移指南

### 从旧版本迁移

1. **更新方法调用**
   ```java
   // 旧版本
   FilterResult<User> result = processor.filter(userList, def, ctx);
   
   // 新版本
   FilterResult<User> result = processor.filterList(userList, def, ctx);
   ```

2. **处理单对象过滤**
   ```java
   // 新功能：直接过滤单个对象
   FilterResult<User> result = processor.filter(user, def, ctx);
   ```

3. **更新异常处理**
   ```java
   // 旧版本
   catch (IllegalArgumentException e) { }
   
   // 新版本
   catch (JsonDslException e) { }
   ```

## 总结

通过这次重构，我们成功解决了以下问题：

1. **类型安全**：明确区分单对象和列表，避免泛型擦除问题
2. **接口清晰**：职责分离，易于理解和维护
3. **向后兼容**：保持现有 API 的兼容性
4. **性能优化**：批量处理和缓存机制
5. **错误处理**：统一的异常处理和详细的失败信息

这次重构为 JSON-DSL 框架提供了更加健壮和类型安全的过滤功能，为后续的功能扩展奠定了良好的基础。 