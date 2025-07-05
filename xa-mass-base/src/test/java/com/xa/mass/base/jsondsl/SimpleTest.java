package com.xa.mass.base.jsondsl;

import com.xa.mass.base.model.Device;

import java.util.List;
import java.util.Map;

/**
 * 新的类型安全 API 测试
 */
public class SimpleTest {
    
    public static void main(String[] args) {
        try {
            System.out.println("=== 测试新的类型安全 JsonDslEngine API ===");
            
            // 注册类型
            TypeRegistry.register("Device", Device.class);
            System.out.println("类型注册完成");
            
            // 测试1：默认返回列表
            String singleDsl = """
                {
                    "MODEL": "Device",
                    "FIELDS": {
                        "deviceId": "device-001",
                        "status": "ONLINE"
                    }
                }
                """;
            
            System.out.println("测试默认返回列表...");
            List<Object> devices = JsonDslEngine.generate(singleDsl);
            System.out.println("默认返回列表测试成功:");
            System.out.println("  返回类型: " + devices.getClass().getSimpleName());
            System.out.println("  列表大小: " + devices.size());
            System.out.println("  结果: " + devices.get(0));
            System.out.println();
            
            // 测试2：指定返回单个对象
            System.out.println("测试指定返回单个对象...");
            Object device = JsonDslEngine.generate(singleDsl, JsonDslEngine.ReturnType.SINGLE);
            System.out.println("指定返回单个对象测试成功:");
            System.out.println("  返回类型: " + device.getClass().getSimpleName());
            System.out.println("  结果: " + device);
            System.out.println();
            
            // 测试3：指定返回列表
            String listDsl = """
                {
                    "MODEL": "Device",
                    "COUNT": 2,
                    "FIELDS": {
                        "deviceId": {"$JOIN": ["device-", "&.index"]},
                        "status": {"$CHOICE": ["ONLINE", "OFFLINE"]}
                    }
                }
                """;
            
            System.out.println("测试指定返回列表...");
            List<Object> deviceList = JsonDslEngine.generate(listDsl, JsonDslEngine.ReturnType.LIST);
            System.out.println("指定返回列表测试成功:");
            System.out.println("  返回类型: " + deviceList.getClass().getSimpleName());
            System.out.println("  列表大小: " + deviceList.size());
            deviceList.forEach(obj -> System.out.println("    " + obj));
            System.out.println();
            
            // 测试4：指定返回映射
            System.out.println("测试指定返回映射...");
            Map<String, Object> deviceMap = JsonDslEngine.generate(singleDsl, JsonDslEngine.ReturnType.MAP);
            System.out.println("指定返回映射测试成功:");
            System.out.println("  返回类型: " + deviceMap.getClass().getSimpleName());
            System.out.println("  映射大小: " + deviceMap.size());
            deviceMap.forEach((key, value) -> System.out.println("    " + key + ": " + value));
            System.out.println();
            
            // 测试5：自动判断返回类型
            System.out.println("测试自动判断返回类型...");
            Object autoResult = JsonDslEngine.generate(singleDsl, JsonDslEngine.ReturnType.AUTO);
            System.out.println("自动判断返回类型测试成功:");
            System.out.println("  返回类型: " + autoResult.getClass().getSimpleName());
            System.out.println("  结果: " + autoResult);
            System.out.println();
            
            // 测试6：便利方法
            System.out.println("测试便利方法...");
            Object single = JsonDslEngine.generateSingle(singleDsl);
            System.out.println("  generateSingle: " + single.getClass().getSimpleName());
            
            List<Object> list = JsonDslEngine.generateList(singleDsl);
            System.out.println("  generateList: " + list.size() + " 个对象");
            
            Map<String, Object> map = JsonDslEngine.generateMap(singleDsl, "Device");
            System.out.println("  generateMap: " + map.keySet());
            
            // 测试7：带类型转换
            System.out.println("测试带类型转换...");
            List<Object> typedList = JsonDslEngine.generateTyped(singleDsl, List.class);
            System.out.println("  generateTyped(List): " + typedList.size() + " 个对象");
            
            Map<String, Object> typedMap = JsonDslEngine.generateTyped(singleDsl, Map.class);
            System.out.println("  generateTyped(Map): " + typedMap.keySet());
            
            System.out.println("\n=== 所有测试完成 ===");
            
        } catch (Exception e) {
            System.err.println("测试过程中发生异常:");
            e.printStackTrace();
        }
    }
} 