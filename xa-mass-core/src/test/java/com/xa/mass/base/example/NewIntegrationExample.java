package com.xa.mass.base.example;

import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;
import com.xa.mass.base.jsondsl.processor.*;
import com.xa.mass.base.model.Device;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NewIntegrationExample {

    public static void main(String[] args) {
        System.out.println("=== 鏂版爣鍑?DSL 闆嗘垚绀轰緥 ===\n");

        // 娉ㄦ剰锛氭柊鏍囧噯 DSL 绯荤粺鏀寔鐩存帴浣跨敤鍏ㄧ被鍚嶏紝鏃犻渶鎻愬墠娉ㄥ唽绫诲瀷锛?

        // 1. 鐢熸垚 300 涓?device锛実roupId: 16-65 闅忔満锛宒eviceId: 0-299
        List<Device> devices = generateDevices();
        System.out.println("鐢熸垚浜?" + devices.size() + " 涓澶?);

        // 杩囨护鍣ㄥ畾涔夛紙鎵嬪姩鏋勯€狅級
        JsonDslDefinition filterDef = buildFilterDef();

        // 杩囨护鍣ㄥ畾涔夛紙澶栭儴JSON瑙ｆ瀽锛?
        String filterJson = """
                {
                  "uniqueId": "device_filter_json",
                  "type": "filter",
                  "description": "杩囨护璁惧锛歞eviceId < 100锛実roupId < 100锛宻tatus = ONLINE (from JSON)",
                  "author": "integration_test",
                  "priority": 10,
                  "fieldDsl": {
                    "deviceId": {"$lt":100},
                    "deviceGroupId": {"$lt": 100},
                    "status": {"$eq": "ONLINE"}
                  },
                  "combineDsl": {
                    "device_group_check": "parseInt(deviceId) < 100 && parseInt(deviceGroupId) < 100",
                    "status_check": "status == 'ONLINE'"
                  }
                
                }
                """;
        JsonDslDefinition filterDefFromJson = JsonDslParser.parse(filterJson);
        filterDefFromJson.validate();

        // 2. 杩囨护锛歞eviceId < 100锛実roupId < 25锛宻tatus = ONLINE
        List<Device> filteredDevices = filterDevices(devices, filterDefFromJson);
        System.out.println("杩囨护鍚庡墿浣?" + filteredDevices.size() + " 涓澶?);

        // 2.1 explain/report: 杈撳嚭琚繃婊よ澶囧強鍘熷洜
        explainFilter(devices, filterDefFromJson);

        // 2.2 鐢ㄥ閮↗SON瀹氫箟鐨勮繃婊ゅ櫒鍐嶆紨绀轰竴娆?
        List<Device> filteredDevicesJson = filterDevices(devices, filterDefFromJson);
        System.out.println("\n[澶栭儴JSON瀹氫箟] 杩囨护鍚庡墿浣?" + filteredDevicesJson.size() + " 涓澶?);
        explainFilter(devices, filterDefFromJson);

        // 3. 鏄剧ず杩囨护缁撴灉
        System.out.println("\n=== 杩囨护缁撴灉 ===");
        filteredDevices.forEach(device ->
                System.out.println("璁惧: " + device.getDeviceId() +
                        ", 缁? " + device.getDeviceGroupId() +
                        ", 鐘舵€? " + device.getStatus())
        );

        // 4. 缁熻淇℃伅
//        printStatistics(devices, filteredDevices);
    }

    /**
     * 浣跨敤鏂版爣鍑?DSL 鐢熸垚璁惧
     */
    private static List<Device> generateDevices() {
        // 1. 鍒涘缓 DSL 瀹氫箟
        JsonDslDefinition definition = new JsonDslDefinition("device_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("鐢熸垚 300 涓祴璇曡澶?);
        definition.setAuthor("integration_test");
        definition.setTags(new String[]{"device", "integration"});
        definition.setPriority(1);

        // 2. 璁剧疆涓婁笅鏂囷紙浣跨敤鍏ㄧ被鍚嶏級
        JsonDslContext context = new JsonDslContext("com.xa.mass.base.model.Device", 300);
        context.setScopeName("Device");
        context.setDebug(false);
        definition.setContext(context);

        // 3. 璁剧疆瀛楁 DSL
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("deviceId", Map.of("$JOIN", Arrays.asList("", "&.index")));
        fieldDsl.put("deviceGroupId", Map.of("$RANGE", Arrays.asList(16, 65)));
        fieldDsl.put("status", Map.of("$CHOICE", Arrays.asList("OFFLINE", "ONLINE")));
        definition.setFieldDsl(fieldDsl);

        // 4. 楠岃瘉骞剁敓鎴?
        definition.validate();
        GenerateProcessor processor = ProcessorRegistry.getGenerateProcessor();
        return processor.generate(definition, new ProcessingContext("test-context"), Device.class);
    }

    /**
     * 浣跨敤鏂版爣鍑?DSL 杩囨护璁惧
     */
    private static List<Device> filterDevices(List<Device> devices, JsonDslDefinition filterDef) {
        String filterConfig = JsonDslParser.toJson(filterDef);
        System.out.println("杩囨护鍣ㄩ厤缃? " + filterConfig);
        FilterProcessor filterProcessor = ProcessorRegistry.getFilterProcessor();
        FilterResult<Device> result = filterProcessor.filterList(devices, filterDef, new ProcessingContext("test-context"));
        List<FilterResult.FilterFailure<Device>> failed = result.getFailed();
        System.out.println("閫氳繃鐨勮澶囨暟: " + result.getPassed().size());
        System.out.println("琚繃婊ょ殑璁惧鍙婂師鍥?");
        if (failed != null) {
            for (FilterResult.FilterFailure<Device> fail : failed) {
                Device d = fail.getData();
                System.out.println("璁惧: " + d.getDeviceId() + ", 缁? " + d.getDeviceGroupId() + ", 鐘舵€? " + d.getStatus()
                        + ", 鏈€氳繃: " + String.join("; ", fail.getReasons()));
            }
        }
        return result.getPassed();
    }

    /**
     * explain/report: 杈撳嚭琚繃婊よ澶囧強鍘熷洜
     */
    private static void explainFilter(List<Device> devices, JsonDslDefinition filterDef) {
        System.out.println("\n=== 杩囨护瑙ｉ噴锛坋xplain/report锛?===");
        FilterProcessor filterProcessor = ProcessorRegistry.getFilterProcessor();
        FilterResult<Device> report = filterProcessor.filterList(devices, filterDef, new ProcessingContext("test-context"));
        System.out.println("閫氳繃鐨勮澶囨暟: " + report.getPassed().size());
        System.out.println("琚繃婊ょ殑璁惧鍙婂師鍥?");
        for (FilterResult.FilterFailure<Device> fail : report.getFailed()) {
            Device d = fail.getData();
            System.out.println("璁惧: " + d.getDeviceId() + ", 缁? " + d.getDeviceGroupId() + ", 鐘舵€? " + d.getStatus()
                    + ", 鏈€氳繃: " + String.join("; ", fail.getReasons()));
        }
        // 缁熻姣忎釜鏉′欢鐨勮鎷掔粷鐜?
        Map<String, Integer> failCount = new HashMap<>();
        int total = devices.size();
        for (FilterResult.FilterFailure<Device> fail : report.getFailed()) {
            for (String cond : fail.getReasons()) {
                failCount.put(cond, failCount.getOrDefault(cond, 0) + 1);
            }
        }
        System.out.println("鍚勬潯浠惰鎷掔粷鐜囷細");
        for (Map.Entry<String, Integer> entry : failCount.entrySet()) {
            double rate = (double) entry.getValue() / total * 100;
            System.out.printf("鏉′欢 [%s] 琚嫆缁濈巼: %.2f%% (%d/%d)\n", entry.getKey(), rate, entry.getValue(), total);
        }
    }

    /**
     * 鎵撳嵃缁熻淇℃伅
     */
    private static void printStatistics(List<Device> allDevices, List<Device> filteredDevices) {
        System.out.println("\n=== 缁熻淇℃伅 ===");

        // 鎬昏澶囨暟
        System.out.println("鎬昏澶囨暟: " + allDevices.size());

        // 鍦ㄧ嚎璁惧鏁?
        long onlineCount = allDevices.stream()
                .filter(device -> "ONLINE".equals(device.getStatus().name()))
                .count();
        System.out.println("鍦ㄧ嚎璁惧鏁? " + onlineCount);

        // deviceId < 100 鐨勮澶囨暟
        long deviceIdLessThan100 = allDevices.stream()
                .filter(device -> {
                    try {
                        return Integer.parseInt(device.getDeviceId()) < 100;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                })
                .count();
        System.out.println("deviceId < 100 鐨勮澶囨暟: " + deviceIdLessThan100);

        // deviceGroupId < 25 鐨勮澶囨暟
        long groupIdLessThan25 = allDevices.stream()
                .filter(device -> {
                    try {
                        return Integer.parseInt(device.getDeviceGroupId()) < 25;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                })
                .count();
        System.out.println("deviceGroupId < 25 鐨勮澶囨暟: " + groupIdLessThan25);

        // 杩囨护鍚庤澶囨暟
        System.out.println("杩囨护鍚庤澶囨暟: " + filteredDevices.size());

        // 杩囨护鐜?
        double filterRate = (double) filteredDevices.size() / allDevices.size() * 100;
        System.out.printf("杩囨护鐜? %.2f%%\n", filterRate);
    }

    /**
     * 鏋勯€犺繃婊ゅ櫒瀹氫箟锛堟墜鍔ㄦ柟寮忥級
     */
    private static JsonDslDefinition buildFilterDef() {
        JsonDslDefinition filterDef = new JsonDslDefinition("device_filter", JsonDslDefinition.DslType.FILTER);
        filterDef.setDescription("杩囨护璁惧锛歞eviceId < 100锛実roupId < 25锛宻tatus = ONLINE");
        filterDef.setAuthor("integration_test");
        filterDef.setPriority(10);
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("deviceId", Map.of("$lt", 100));
        fieldDsl.put("deviceGroupId", Map.of("$lt", 25));
        fieldDsl.put("status", Map.of("$eq", "ONLINE"));
        filterDef.setFieldDsl(fieldDsl);
        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("device_group_check", "parseInt(deviceId) < 100 && parseInt(deviceGroupId) < 25");
        combineDsl.put("status_check", "status == 'ONLINE'");
        filterDef.setCombineDsl(combineDsl);
        filterDef.validate();
        return filterDef;
    }
}
