package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.model.JsonDslContext;

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
        
        // 示例1：生成设备数据
        exampleGenerate(generateProcessor);
        
        // 示例2：过滤设备数据
        exampleFilter(filterProcessor);
        
        // 示例3：转换设备数据
        exampleTransform(transformProcessor);
        
        // 示例4：校验设备数据
        exampleValidate(validateProcessor);
    }
    
    /**
     * 生成示例
     */
    private static void exampleGenerate(GenerateProcessor generateProcessor) {
        System.out.println("=== 生成示例 ===");
        
        // 创建 DSL 定义
        JsonDslDefinition dsl = new JsonDslDefinition("device-generator", JsonDslDefinition.DslType.GENERATE);
        dsl.setContext(new JsonDslContext("com.xa.mass.base.model.Device", 3));
        dsl.setFieldDsl(Map.of(
            "deviceId", "$JOIN(['device-', '&.index'])",
            "status", "$CHOICE(['ONLINE', 'OFFLINE'])",
            "batteryLevel", "$RANDOM_INT(0, 100)"
        ));
        
        // 创建处理上下文
        ProcessingContext context = new ProcessingContext("generate-example");
        context.setDebug(true);
        
        // 生成设备列表
        List<Device> devices = generateProcessor.generate(dsl, context, Device.class);
        
        System.out.println("生成设备数量: " + devices.size());
        devices.forEach(device -> System.out.println("设备: " + device.getDeviceId() + ", 状态: " + device.getStatus()));
    }
    
    /**
     * 过滤示例
     */
    private static void exampleFilter(FilterProcessor filterProcessor) {
        System.out.println("\n=== 过滤示例 ===");
        
        // 创建测试数据
        List<Device> devices = List.of(
            createDevice("device-1", "ONLINE", 80),
            createDevice("device-2", "OFFLINE", 20),
            createDevice("device-3", "ONLINE", 95)
        );
        
        // 创建 DSL 定义
        JsonDslDefinition dsl = new JsonDslDefinition("online-filter", JsonDslDefinition.DslType.FILTER);
        dsl.setFieldDsl(Map.of(
            "status", Map.of("eq", "ONLINE"),
            "batteryLevel", Map.of("gte", 50)
        ));
        
        // 创建处理上下文
        ProcessingContext context = new ProcessingContext("filter-example");
        context.setDebug(true);
        
        // 过滤设备列表
        List<Device> filteredDevices = filterProcessor.filter(devices, dsl, context);
        
        System.out.println("原始设备数量: " + devices.size());
        System.out.println("过滤后设备数量: " + filteredDevices.size());
        filteredDevices.forEach(device -> System.out.println("过滤后设备: " + device.getDeviceId()));
    }
    
    /**
     * 转换示例
     */
    private static void exampleTransform(TransformProcessor transformProcessor) {
        System.out.println("\n=== 转换示例 ===");
        
        // 创建测试数据
        Device device = createDevice("device-1", "ONLINE", 80);
        
        // 创建 DSL 定义
        JsonDslDefinition dsl = new JsonDslDefinition("device-transform", JsonDslDefinition.DslType.TRANSFORM);
        dsl.setFieldDsl(Map.of(
            "deviceId", "$JOIN(['transformed-', '&.deviceId'])",
            "status", "$EXPR(status == 'ONLINE' ? 'ACTIVE' : 'INACTIVE')"
        ));
        
        // 创建处理上下文
        ProcessingContext context = new ProcessingContext("transform-example");
        context.setDebug(true);
        
        // 转换设备
        Device transformedDevice = transformProcessor.transform(device, dsl, context);
        
        System.out.println("原始设备: " + device.getDeviceId() + ", 状态: " + device.getStatus());
        System.out.println("转换后设备: " + transformedDevice.getDeviceId() + ", 状态: " + transformedDevice.getStatus());
    }
    
    /**
     * 校验示例
     */
    private static void exampleValidate(ValidateProcessor validateProcessor) {
        System.out.println("\n=== 校验示例 ===");
        
        // 创建测试数据
        Device device = createDevice("device-1", "ONLINE", 80);
        
        // 创建 DSL 定义
        JsonDslDefinition dsl = new JsonDslDefinition("device-validate", JsonDslDefinition.DslType.VALIDATE);
        dsl.setFieldDsl(Map.of(
            "deviceId", "$EXPR(deviceId != null && deviceId.length() > 0)",
            "batteryLevel", "$EXPR(batteryLevel >= 0 && batteryLevel <= 100)"
        ));
        
        // 创建处理上下文
        ProcessingContext context = new ProcessingContext("validate-example");
        context.setDebug(true);
        
        // 校验设备
        List<String> errors = validateProcessor.validate(device, dsl, context);
        
        if (errors.isEmpty()) {
            System.out.println("设备校验通过");
        } else {
            System.out.println("设备校验失败，错误信息:");
            errors.forEach(System.out::println);
        }
    }
    
    /**
     * 创建测试设备
     */
    private static Device createDevice(String deviceId, String status, int batteryLevel) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setStatus(status);
        device.setBatteryLevel(batteryLevel);
        return device;
    }
    
    /**
     * 设备模型类（示例）
     */
    public static class Device {
        private String deviceId;
        private String status;
        private int batteryLevel;
        
        public String getDeviceId() { return deviceId; }
        public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
        
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        
        public int getBatteryLevel() { return batteryLevel; }
        public void setBatteryLevel(int batteryLevel) { this.batteryLevel = batteryLevel; }
    }
} 