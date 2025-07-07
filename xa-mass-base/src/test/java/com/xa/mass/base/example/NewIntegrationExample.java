package com.xa.mass.base.example;

import com.xa.mass.base.jsondsl.JsonDslEngine;
import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;
import com.xa.mass.base.jsondsl.processor.DefaultGenerateProcessor;
import com.xa.mass.base.jsondsl.processor.GenerateProcessor;
import com.xa.mass.base.jsondsl.processor.GenerateProcessorTest;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class NewIntegrationExample {

    public static void main(String[] args) {
        System.out.println("=== 新标准 DSL 集成示例 ===\n");
        
        // 注意：新标准 DSL 系统支持直接使用全类名，无需提前注册类型！
        
        // 1. 生成 300 个 device，groupId: 16-65 随机，deviceId: 0-299
        List<Device> devices = generateDevices();
        System.out.println("生成了 " + devices.size() + " 个设备");
        
        // 2. 过滤：deviceId < 100，groupId < 25，status = ONLINE
        List<Device> filteredDevices = filterDevices(devices);
        System.out.println("过滤后剩余 " + filteredDevices.size() + " 个设备");
        
        // 3. 显示过滤结果
        System.out.println("\n=== 过滤结果 ===");
        filteredDevices.forEach(device -> 
            System.out.println("设备: " + device.getDeviceId() + 
                ", 组: " + device.getGroupId() + 
                ", 状态: " + device.getStatus())
        );
        
        // 4. 统计信息
        printStatistics(devices, filteredDevices);
    }
    
    /**
     * 使用新标准 DSL 生成设备
     */
    private static List<Device> generateDevices() {
        // 1. 创建 DSL 定义
        JsonDslDefinition definition = new JsonDslDefinition("device_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("生成 300 个测试设备");
        definition.setAuthor("integration_test");
        definition.setTags(new String[]{"device", "integration"});
        definition.setPriority(1);
        
        // 2. 设置上下文（使用全类名）
        JsonDslContext context = new JsonDslContext("com.xa.mass.base.model.Device", 300);
        context.setScopeName("Device");
        context.setDebug(false);
        definition.setContext(context);
        
        // 3. 设置字段 DSL
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("deviceId", Map.of("$JOIN", Arrays.asList("", "&.index")));
        fieldDsl.put("groupId", Map.of("$RANGE", Arrays.asList(16, 65)));
        fieldDsl.put("status", Map.of("$CHOICE", Arrays.asList("OFFLINE", "ONLINE")));
        definition.setFieldDsl(fieldDsl);
        
        // 4. 验证并生成
        definition.validate();
        String legacyFormat = JsonDslParser.toLegacyFormat(definition);
         GenerateProcessor<Device> processor=new DefaultGenerateProcessor<>();
//        processor.generate(definition)
        return JsonDslEngine.generateList(legacyFormat, Device.class);
    }
    
    /**
     * 使用新标准 DSL 过滤设备
     */
    private static List<Device> filterDevices(List<Device> devices) {
        // 1. 创建过滤器 DSL 定义
        JsonDslDefinition filterDef = new JsonDslDefinition("device_filter", JsonDslDefinition.DslType.FILTER);
        filterDef.setDescription("过滤设备：deviceId < 100，groupId < 25，status = ONLINE");
        filterDef.setAuthor("integration_test");
        filterDef.setPriority(10);
        
        // 2. 设置字段过滤条件
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("deviceId", Map.of("lt", 100));
        fieldDsl.put("groupId", Map.of("lt", 25));
        fieldDsl.put("status", Map.of("eq", "ONLINE"));
        filterDef.setFieldDsl(fieldDsl);
        
        // 3. 设置组合过滤条件（可选）
        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("device_group_check", "parseInt(deviceId) < 100 && parseInt(groupId) < 25");
        combineDsl.put("status_check", "status == 'ONLINE'");
        filterDef.setCombineDsl(combineDsl);
        
        // 4. 验证过滤器
        filterDef.validate();
        
        // 5. 应用过滤器
        String filterConfig = JsonDslParser.toLegacyFormat(filterDef);
        System.out.println("过滤器配置: " + filterConfig);

//        JsonDslEngine.filter(devices,filterConfig);
        
        // 6. 使用传统方式过滤（因为新标准 DSL 的过滤器还在开发中）
        return devices.stream()
            .filter(device -> {
                try {
                    int deviceId = Integer.parseInt(device.getDeviceId());
                    int groupId = Integer.parseInt(device.getGroupId());
                    return deviceId < 100 && groupId < 25 && "ONLINE".equals(device.getStatus().name());
                } catch (NumberFormatException e) {
                    return false;
                }
            })
            .collect(Collectors.toList());
    }
    
    /**
     * 打印统计信息
     */
    private static void printStatistics(List<Device> allDevices, List<Device> filteredDevices) {
        System.out.println("\n=== 统计信息 ===");
        
        // 总设备数
        System.out.println("总设备数: " + allDevices.size());
        
        // 在线设备数
        long onlineCount = allDevices.stream()
            .filter(device -> "ONLINE".equals(device.getStatus().name()))
            .count();
        System.out.println("在线设备数: " + onlineCount);
        
        // deviceId < 100 的设备数
        long deviceIdLessThan100 = allDevices.stream()
            .filter(device -> {
                try {
                    return Integer.parseInt(device.getDeviceId()) < 100;
                } catch (NumberFormatException e) {
                    return false;
                }
            })
            .count();
        System.out.println("deviceId < 100 的设备数: " + deviceIdLessThan100);
        
        // groupId < 25 的设备数
        long groupIdLessThan25 = allDevices.stream()
            .filter(device -> {
                try {
                    return Integer.parseInt(device.getGroupId()) < 25;
                } catch (NumberFormatException e) {
                    return false;
                }
            })
            .count();
        System.out.println("groupId < 25 的设备数: " + groupIdLessThan25);
        
        // 过滤后设备数
        System.out.println("过滤后设备数: " + filteredDevices.size());
        
        // 过滤率
        double filterRate = (double) filteredDevices.size() / allDevices.size() * 100;
        System.out.printf("过滤率: %.2f%%\n", filterRate);
    }
}
