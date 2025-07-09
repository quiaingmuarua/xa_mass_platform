package com.xa.mass.base.example;

import com.xa.mass.base.jsondsl.JsonDslEngine;
import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;
import com.xa.mass.base.jsondsl.processor.*;
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
        
        // 过滤器定义（手动构造）
        JsonDslDefinition filterDef = buildFilterDef();
        
        // 过滤器定义（外部JSON解析）
        String filterJson = """
        {
          "uniqueId": "device_filter_json",
          "type": "filter",
          "description": "过滤设备：deviceId < 100，groupId < 100，status = ONLINE (from JSON)",
          "author": "integration_test",
          "priority": 10,
          "fieldDsl": {
            "deviceId": {"$lt":100},
            "groupId": {"$lt": 100},
            "status": {"$eq": "ONLINE"}
          },
          "combineDsl": {
            "device_group_check": "parseInt(deviceId) < 100 && parseInt(groupId) < 100",
            "status_check": "status == 'ONLINE'"
          }

        }
        """;
        JsonDslDefinition filterDefFromJson = JsonDslParser.parse(filterJson);
        filterDefFromJson.validate();
        
        // 2. 过滤：deviceId < 100，groupId < 25，status = ONLINE
        List<Device> filteredDevices = filterDevices(devices, filterDefFromJson);
        System.out.println("过滤后剩余 " + filteredDevices.size() + " 个设备");
        
        // 2.1 explain/report: 输出被过滤设备及原因
        explainFilter(devices, filterDefFromJson);
        
        // 2.2 用外部JSON定义的过滤器再演示一次
        List<Device> filteredDevicesJson = filterDevices(devices, filterDefFromJson);
        System.out.println("\n[外部JSON定义] 过滤后剩余 " + filteredDevicesJson.size() + " 个设备");
        explainFilter(devices, filterDefFromJson);
        
        // 3. 显示过滤结果
        System.out.println("\n=== 过滤结果 ===");
        filteredDevices.forEach(device -> 
            System.out.println("设备: " + device.getDeviceId() + 
                ", 组: " + device.getGroupId() + 
                ", 状态: " + device.getStatus())
        );
        
        // 4. 统计信息
//        printStatistics(devices, filteredDevices);
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
        GenerateProcessor processor = ProcessorRegistry.getGenerateProcessor();
        return processor.generate(definition,new ProcessingContext("test-context"),Device.class);
    }
    
    /**
     * 使用新标准 DSL 过滤设备
     */
    private static List<Device> filterDevices(List<Device> devices, JsonDslDefinition filterDef) {
        String filterConfig = JsonDslParser.toJson(filterDef);
        System.out.println("过滤器配置: " + filterConfig);
        FilterProcessor filterProcessor = ProcessorRegistry.getFilterProcessor();
        FilterResult<Device> result = filterProcessor.filterList(devices, filterDef, new ProcessingContext("test-context"));
        List<FilterResult.FilterFailure<Device>> failed = result.getFailed();
        System.out.println("通过的设备数: " + result.getPassed().size());
        System.out.println("被过滤的设备及原因:");
        if (failed != null) {
            for (FilterResult.FilterFailure<Device> fail : failed) {
                Device d = fail.getData();
                System.out.println("设备: " + d.getDeviceId() + ", 组: " + d.getGroupId() + ", 状态: " + d.getStatus()
                    + ", 未通过: " + String.join("; ", fail.getReasons()));
            }
        }
        return result.getPassed();
    }
    
    /**
     * explain/report: 输出被过滤设备及原因
     */
    private static void explainFilter(List<Device> devices, JsonDslDefinition filterDef) {
        System.out.println("\n=== 过滤解释（explain/report） ===");
        FilterProcessor filterProcessor = ProcessorRegistry.getFilterProcessor();
        FilterResult<Device> report = filterProcessor.filterList(devices, filterDef, new ProcessingContext("test-context"));
        System.out.println("通过的设备数: " + report.getPassed().size());
        System.out.println("被过滤的设备及原因:");
        for (FilterResult.FilterFailure<Device> fail : report.getFailed()) {
            Device d = fail.getData();
            System.out.println("设备: " + d.getDeviceId() + ", 组: " + d.getGroupId() + ", 状态: " + d.getStatus()
                + ", 未通过: " + String.join("; ", fail.getReasons()));
        }
        // 统计每个条件的被拒绝率
        Map<String, Integer> failCount = new HashMap<>();
        int total = devices.size();
        for (FilterResult.FilterFailure<Device> fail : report.getFailed()) {
            for (String cond : fail.getReasons()) {
                failCount.put(cond, failCount.getOrDefault(cond, 0) + 1);
            }
        }
        System.out.println("各条件被拒绝率：");
        for (Map.Entry<String, Integer> entry : failCount.entrySet()) {
            double rate = (double) entry.getValue() / total * 100;
            System.out.printf("条件 [%s] 被拒绝率: %.2f%% (%d/%d)\n", entry.getKey(), rate, entry.getValue(), total);
        }
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

    /**
     * 构造过滤器定义（手动方式）
     */
    private static JsonDslDefinition buildFilterDef() {
        JsonDslDefinition filterDef = new JsonDslDefinition("device_filter", JsonDslDefinition.DslType.FILTER);
        filterDef.setDescription("过滤设备：deviceId < 100，groupId < 25，status = ONLINE");
        filterDef.setAuthor("integration_test");
        filterDef.setPriority(10);
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("deviceId", Map.of("$lt", 100));
        fieldDsl.put("groupId", Map.of("$lt", 25));
        fieldDsl.put("status", Map.of("$eq", "ONLINE"));
        filterDef.setFieldDsl(fieldDsl);
        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("device_group_check", "parseInt(deviceId) < 100 && parseInt(groupId) < 25");
        combineDsl.put("status_check", "status == 'ONLINE'");
        filterDef.setCombineDsl(combineDsl);
        filterDef.validate();
        return filterDef;
    }
}
