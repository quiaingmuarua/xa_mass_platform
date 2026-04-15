# 强类型处理器重构文档

## 概述

本次重构将原有的粗糙 `JsonDslProcessor` 接口重构为强类型的泛型子接口，提供了更好的类型安全性和更清晰的 API 设计。

## 重构内容

### 1. 基础接口

**原接口**：

```java
public interface JsonDslProcessor {
    Object process(JsonDslDefinition definition, ProcessingContext context);
    boolean supports(JsonDslDefinition.DslType type);
    String getName();
    int getPriority();
}
```

**新接口**：

```java
public interface JsonDslProcessor {
    boolean supports(JsonDslDefinition.DslType type);
    String getName();
    int getPriority();
}
```

### 2. 强类型子接口

#### GenerateProcessor<T>

```java
public interface GenerateProcessor<T> extends JsonDslProcessor {
    List<T> generate(JsonDslDefinition definition, ProcessingContext context, Class<T> targetType);
}
```

#### FilterProcessor<T>

```java
public interface FilterProcessor<T> extends JsonDslProcessor {
    List<T> filter(List<T> input, JsonDslDefinition definition, ProcessingContext context);
}
```

#### TransformProcessor<T>

```java
public interface TransformProcessor<T> extends JsonDslProcessor {
    T transform(T input, JsonDslDefinition definition, ProcessingContext context);
}
```

#### ValidateProcessor<T>

```java
public interface ValidateProcessor<T> extends JsonDslProcessor {
    List<String> validate(T obj, JsonDslDefinition definition, ProcessingContext context);
}
```

### 3. 默认实现

- `DefaultGenerateProcessor<T>` - 默认生成处理器
- `DefaultFilterProcessor<T>` - 默认过滤处理器
- `DefaultTransformProcessor<T>` - 默认转换处理器
- `DefaultValidateProcessor<T>` - 默认校验处理器

## 设计优势

### 1. 类型安全

- 编译时类型检查，避免运行时类型转换错误
- 泛型约束确保输入输出类型一致性
- IDE 自动补全和错误提示

### 2. 清晰的 API

- 每个处理器接口职责单一，方法签名明确
- 参数类型明确，无需猜测参数含义
- 返回值类型明确，无需类型转换

### 3. 更好的扩展性

- 可以针对特定类型实现专用处理器
- 支持自定义处理器实现
- 便于单元测试和模拟

### 4. 代码可读性

- 方法名和参数名更加直观
- 类型信息在编译时就能确定
- 减少运行时类型检查

## 使用示例

### 基本使用

```java
// 创建强类型处理器
GenerateProcessor<Worker> generateProcessor = new DefaultGenerateProcessor<>();
FilterProcessor<Worker> filterProcessor = new DefaultFilterProcessor<>();
TransformProcessor<Worker> transformProcessor = new DefaultTransformProcessor<>();
ValidateProcessor<Worker> validateProcessor = new DefaultValidateProcessor<>();

// 生成设备数据
List<Worker> workers = generateProcessor.generate(dsl, context, Worker.class);

// 过滤设备数据
List<Worker> filteredWorkers = filterProcessor.filter(workers, dsl, context);

// 转换设备数据
Worker transformedWorker = transformProcessor.transform(worker, dsl, context);

// 校验设备数据
List<String> errors = validateProcessor.validate(worker, dsl, context);
```

### 自定义处理器

```java
public class CustomWorkerProcessor implements GenerateProcessor<Worker> {
    @Override
    public List<Worker> generate(JsonDslDefinition definition, ProcessingContext context, Class<Worker> targetType) {
        // 自定义生成逻辑
        return customGenerateLogic(definition, context);
    }

    @Override
    public String getName() {
        return "CustomWorkerProcessor";
    }

    @Override
    public int getPriority() {
        return 150;
    }
}
```

## 迁移指南

### 从旧接口迁移

**旧代码**：

```java
JsonDslProcessor processor = new GenerateProcessor();
Object result = processor.process(definition, context);
List<Worker> workers = (List<Worker>) result;
```

**新代码**：

```java
GenerateProcessor<Worker> processor = new DefaultGenerateProcessor<>();
List<Worker> workers = processor.generate(definition, context, Worker.class);
```

### 兼容性处理

- 旧的处理逻辑已迁移到默认实现类中
- 原有的 `JsonDslProcessorEngine` 可以继续使用
- 新的强类型接口提供了更好的类型安全

## 最佳实践

### 1. 类型选择

- 优先使用具体的业务类型而不是 `Object`
- 为不同的业务模型创建专门的处理器
- 利用泛型约束确保类型安全

### 2. 处理器实现

- 实现类应该专注于单一职责
- 提供清晰的错误信息和调试日志
- 遵循接口契约，确保行为一致性

### 3. 性能考虑

- 避免不必要的类型转换
- 合理使用缓存机制
- 注意内存使用，特别是处理大量数据时

### 4. 测试策略

- 为每个处理器编写单元测试
- 测试边界条件和异常情况
- 使用模拟对象测试复杂场景

## 总结

这次重构显著提升了 JSON-DSL 处理器的类型安全性和 API 清晰度：

1. **类型安全**：编译时类型检查，减少运行时错误
2. **API 清晰**：方法签名明确，职责单一
3. **扩展性好**：支持自定义实现，便于测试
4. **向后兼容**：保持与现有代码的兼容性

新的强类型处理器架构为 JSON-DSL 框架提供了更坚实的基础，支持更复杂的业务场景和更好的开发体验。 