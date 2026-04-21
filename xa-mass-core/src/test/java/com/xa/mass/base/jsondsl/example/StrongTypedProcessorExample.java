package com.xa.mass.base.jsondsl.example;

import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.processor.FilterProcessor;
import com.xa.mass.base.jsondsl.processor.FilterResult;
import com.xa.mass.base.jsondsl.processor.GenerateProcessor;
import com.xa.mass.base.jsondsl.processor.ProcessingContext;
import com.xa.mass.base.jsondsl.processor.ProcessorRegistry;
import com.xa.mass.base.jsondsl.processor.TransformProcessor;
import com.xa.mass.base.jsondsl.processor.ValidateProcessor;

import java.util.List;
import java.util.Map;

/**
 * 强类型处理器使用示例
 * <p>
 * 展示如何使用新的强类型泛型处理器接口
 * </p>
 */
public class StrongTypedProcessorExample {

    public static void main(String[] args) {
        // 创建强类型处理器
        GenerateProcessor generateProcessor = ProcessorRegistry.getGenerateProcessor();
        FilterProcessor filterProcessor = ProcessorRegistry.getFilterProcessor();
        TransformProcessor transformProcessor = ProcessorRegistry.getTransformProcessor();
        ValidateProcessor validateProcessor = ProcessorRegistry.getValidateProcessor();

        // 示例1：生成 Worker 数据
        exampleGenerate(generateProcessor);

        // 示例2：过滤 Worker 数据
        exampleFilter(filterProcessor);

        // 示例3：转换 Worker 数据
        exampleTransform(transformProcessor);

        // 示例4：校验 Worker 数据
        exampleValidate(validateProcessor);
    }

    /**
     * 生成示例
     */
    private static void exampleGenerate(GenerateProcessor generateProcessor) {
        System.out.println("=== 生成示例 ===");

        JsonDslDefinition dsl = new JsonDslDefinition("worker-generator", JsonDslDefinition.DslType.GENERATE);
        dsl.setContext(new JsonDslContext(StrongTypedProcessorExample.Worker.class.getName(), 3));
        dsl.setFieldDsl(Map.of(
                "workerId", Map.of("$JOIN", List.of("worker-", "&.index")),
                "status", Map.of("$CHOICE", List.of("ONLINE", "OFFLINE")),
                "batteryLevel", Map.of("$RANDOM_INT", List.of(0, 100))
        ));

        ProcessingContext context = new ProcessingContext("generate-example");
        context.setDebug(true);

        List<Worker> workers = generateProcessor.generate(dsl, context, Worker.class);

        System.out.println("生成 Worker 数量: " + workers.size());
        workers.forEach(worker -> System.out.println("Worker: " + worker.getWorkerId() + ", 状态: " + worker.getStatus()));
    }

    /**
     * 过滤示例
     */
    private static void exampleFilter(FilterProcessor filterProcessor) {
        System.out.println("\n=== 过滤示例 ===");

        List<Worker> workers = List.of(
                createWorker("worker-1", "ONLINE", 80),
                createWorker("worker-2", "OFFLINE", 20),
                createWorker("worker-3", "ONLINE", 95)
        );

        JsonDslDefinition dsl = new JsonDslDefinition("online-filter", JsonDslDefinition.DslType.FILTER);
        dsl.setFieldDsl(Map.of(
                "status", Map.of("eq", "ONLINE"),
                "batteryLevel", Map.of("gte", 50)
        ));

        ProcessingContext context = new ProcessingContext("filter-example");
        context.setDebug(true);

        FilterResult<Worker> filteredResult = filterProcessor.filterList(workers, dsl, context);
        List<Worker> filteredWorkers = filteredResult.getPassed();

        System.out.println("原始 Worker 数量: " + workers.size());
        System.out.println("过滤后 Worker 数量: " + filteredWorkers.size());
        filteredWorkers.forEach(worker -> System.out.println("过滤后 Worker: " + worker.getWorkerId()));
    }

    /**
     * 转换示例
     */
    private static void exampleTransform(TransformProcessor transformProcessor) {
        System.out.println("\n=== 转换示例 ===");

        Worker worker = createWorker("worker-1", "ONLINE", 80);

        JsonDslDefinition dsl = new JsonDslDefinition("worker-transform", JsonDslDefinition.DslType.TRANSFORM);
        dsl.setFieldDsl(Map.of(
                "workerId", "$JOIN(['transformed-', '&.workerId'])",
                "status", Map.of("$EXPR", "status == 'ONLINE' ? 'ACTIVE' : 'INACTIVE'")
        ));

        ProcessingContext context = new ProcessingContext("transform-example");
        context.setDebug(true);

        Worker transformedWorker = transformProcessor.transform(worker, dsl, context);

        System.out.println("原始 Worker: " + worker.getWorkerId() + ", 状态: " + worker.getStatus());
        System.out.println("转换后 Worker: " + transformedWorker.getWorkerId() + ", 状态: " + transformedWorker.getStatus());
    }

    /**
     * 校验示例
     */
    private static void exampleValidate(ValidateProcessor validateProcessor) {
        System.out.println("\n=== 校验示例 ===");

        Worker worker = createWorker("worker-1", "ONLINE", 80);

        JsonDslDefinition dsl = new JsonDslDefinition("worker-validate", JsonDslDefinition.DslType.VALIDATE);
        dsl.setFieldDsl(Map.of(
                "workerId", Map.of("$EXPR", "workerId != null && workerId.length() > 0"),
                "batteryLevel", Map.of("$EXPR", "batteryLevel >= 0 && batteryLevel <= 100")
        ));

        ProcessingContext context = new ProcessingContext("validate-example");
        context.setDebug(true);

        List<String> errors = validateProcessor.validate(worker, dsl, context);

        if (errors.isEmpty()) {
            System.out.println("Worker 校验通过");
        } else {
            System.out.println("Worker 校验失败，错误信息:");
            errors.forEach(System.out::println);
        }
    }

    /**
     * 创建测试 Worker
     */
    private static Worker createWorker(String workerId, String status, int batteryLevel) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setStatus(status);
        worker.setBatteryLevel(batteryLevel);
        return worker;
    }

    /**
     * Worker 模型类（示例）
     */
    public static class Worker {
        private String workerId;
        private String status;
        private int batteryLevel;

        public String getWorkerId() {
            return workerId;
        }

        public void setWorkerId(String workerId) {
            this.workerId = workerId;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public int getBatteryLevel() {
            return batteryLevel;
        }

        public void setBatteryLevel(int batteryLevel) {
            this.batteryLevel = batteryLevel;
        }
    }
}
