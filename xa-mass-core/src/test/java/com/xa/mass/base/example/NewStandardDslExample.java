package com.xa.mass.base.example;

import com.xa.mass.base.jsondsl.JsonDslEngine;
import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;
import com.xa.mass.base.model.Device;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 鏂版爣鍑?DSL 浣跨敤绀轰緥
 * <p>
 * 灞曠ず濡備綍浣跨敤鏂扮殑鏍囧噯鍖?DSL 缁撴瀯鏇夸唬鏃х殑杩囨椂鏂规硶
 * 鏂版爣鍑?DSL 绯荤粺鏀寔鐩存帴浣跨敤鍏ㄧ被鍚嶏紝鏃犻渶鎻愬墠娉ㄥ唽绫诲瀷
 * </p>
 */
public class NewStandardDslExample {

    public static void main(String[] args) {
        System.out.println("=== 鏂版爣鍑?DSL 浣跨敤绀轰緥 ===\n");
        System.out.println("娉ㄦ剰锛氭柊鏍囧噯 DSL 绯荤粺鏀寔鐩存帴浣跨敤鍏ㄧ被鍚嶏紝鏃犻渶鎻愬墠娉ㄥ唽绫诲瀷锛乗n");

        // 绀轰緥1: 鍩烘湰鐢熸垚 DSL锛堜娇鐢ㄥ叏绫诲悕锛?
        example1_BasicGenerateDsl();

        // 绀轰緥2: 澶嶆潅鐢熸垚 DSL锛堜娇鐢ㄥ叏绫诲悕锛?
        example2_ComplexGenerateDsl();

        // 绀轰緥3: 杩囨护鍣?DSL
        example3_FilterDsl();

        // 绀轰緥4: 杞崲 DSL
        example4_TransformDsl();

        // 绀轰緥5: 楠岃瘉 DSL
        example5_ValidateDsl();

        // 绀轰緥6: 浠?JSON 瑙ｆ瀽 DSL锛堜娇鐢ㄥ叏绫诲悕锛?
        example6_ParseFromJson();
    }

    /**
     * 绀轰緥1: 鍩烘湰鐢熸垚 DSL锛堜娇鐢ㄥ叏绫诲悕锛屾棤闇€娉ㄥ唽锛?
     */
    private static void example1_BasicGenerateDsl() {
        System.out.println("--- 绀轰緥1: 鍩烘湰鐢熸垚 DSL锛堜娇鐢ㄥ叏绫诲悕锛?---");

        // 1. 鍒涘缓 DSL 瀹氫箟
        JsonDslDefinition definition = new JsonDslDefinition("basic_device_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("鐢熸垚鍩烘湰璁惧鏁版嵁");
        definition.setAuthor("system");
        definition.setTags(new String[]{"device", "basic"});
        definition.setPriority(1);

        // 2. 璁剧疆涓婁笅鏂囷紙浣跨敤鍏ㄧ被鍚嶏級
        JsonDslContext context = new JsonDslContext("com.xa.mass.base.model.Device", 3);
        context.setScopeName("Device");
        context.setDebug(true);
        definition.setContext(context);

        // 3. 璁剧疆瀛楁 DSL
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("deviceId", Map.of("$JOIN", Arrays.asList("device-", "&.index")));
        fieldDsl.put("status", Map.of("$CHOICE", Arrays.asList("ONLINE", "OFFLINE")));
        fieldDsl.put("deviceGroupId", Map.of("$CHOICE", Arrays.asList("us", "gb", "cn")));
        definition.setFieldDsl(fieldDsl);

        // 4. 楠岃瘉 DSL
        definition.validate();

        // 5. 杞崲涓轰紶缁熸牸寮忓苟鐢熸垚鏁版嵁
        String legacyFormat = JsonDslParser.toJson(definition);
        List<Device> devices = JsonDslEngine.generateList(legacyFormat, Device.class);

        System.out.println("鐢熸垚鐨勮澶囨暟閲? " + devices.size());
        devices.forEach(device ->
                System.out.println("  - " + device.getDeviceId() + " (" + device.getStatus() + ", " + device.getDeviceGroupId() + ")")
        );
        System.out.println();
    }

    /**
     * 绀轰緥2: 澶嶆潅鐢熸垚 DSL锛堝寘鍚祵濂楀拰琛ㄨ揪寮忥紝浣跨敤鍏ㄧ被鍚嶏級
     */
    private static void example2_ComplexGenerateDsl() {
        System.out.println("--- 绀轰緥2: 澶嶆潅鐢熸垚 DSL锛堜娇鐢ㄥ叏绫诲悕锛?---");

        // 1. 鍒涘缓 DSL 瀹氫箟
        JsonDslDefinition definition = new JsonDslDefinition("complex_device_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("鐢熸垚鍖呭惈宓屽浠诲姟鐨勫鏉傝澶囨暟鎹?);
        definition.setAuthor("advanced_user");
        definition.setTags(new String[]{"device", "complex", "nested"});
        definition.setPriority(2);

        // 2. 璁剧疆涓婁笅鏂囷紙浣跨敤鍏ㄧ被鍚嶏級
        JsonDslContext context = new JsonDslContext("com.xa.mass.base.model.Device", 2);
        context.setScopeName("Device");
        context.setDebug(true);
        context.setStrict(true);
        definition.setContext(context);

        // 3. 璁剧疆瀛楁 DSL
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("deviceId", Map.of("$JOIN", Arrays.asList("complex-device-", "&.index")));
        fieldDsl.put("status", Map.of("$CHOICE", Arrays.asList("ONLINE", "OFFLINE")));
        fieldDsl.put("deviceGroupId", Map.of("$CHOICE", Arrays.asList("us", "gb", "cn")));
        fieldDsl.put("agentVersion", Map.of("$JOIN", Arrays.asList("2.0.", "&.index")));

        // 宓屽浠诲姟锛堜娇鐢ㄥ叏绫诲悕锛?
        Map<String, Object> tasksField = new HashMap<>();
        tasksField.put("TYPE", "LIST");
        tasksField.put("COUNT", 2);
        tasksField.put("MODEL", "com.xa.mass.base.model.Task");
        Map<String, Object> taskFields = new HashMap<>();
        taskFields.put("tid", Map.of("$UUID", true));
        taskFields.put("taskName", Map.of("$JOIN", Arrays.asList("ComplexTask-", "&.index", "-of-Device-", "&Device.index")));
        taskFields.put("taskRoutingCountryCode", "&Device.deviceGroupId");
        taskFields.put("taskTargetNumber", Map.of("$RANGE", Arrays.asList(50, 200)));
        taskFields.put("batchSize", Map.of("$RANGE", Arrays.asList(2, 8)));
        tasksField.put("FIELDS", taskFields);
        fieldDsl.put("tasks", tasksField);

        // 琛ㄨ揪寮忓瓧娈?
        Map<String, Object> onlineStrategy = new HashMap<>();
        onlineStrategy.put("$EXPR", Map.of(
                "lang", "ql",
                "expr", "status == 'OFFLINE' ? 0 : range(10, 100)"
        ));
        fieldDsl.put("onlineStrategy", onlineStrategy);

        definition.setFieldDsl(fieldDsl);

        // 4. 璁剧疆缁勫悎瑙勫垯
        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("device_task_balance", "tasks.size() <= 3 ? 'balanced' : 'overloaded'");
        combineDsl.put("status_performance", "status == 'ONLINE' && agentVersion.startsWith('2.0') ? 'high_performance' : 'standard'");
        combineDsl.put("group_capacity", "deviceGroupId == 'us' ? 100 : deviceGroupId == 'gb' ? 50 : 30");
        definition.setCombineDsl(combineDsl);

        // 5. 璁剧疆鎵╁睍淇℃伅
        Map<String, Object> extensions = new HashMap<>();
        Map<String, Object> businessRules = new HashMap<>();
        businessRules.put("max_tasks_per_device", 5);
        businessRules.put("preferred_groups", Arrays.asList("us", "gb"));
        extensions.put("business_rules", businessRules);
        definition.setExtensions(extensions);

        // 6. 楠岃瘉骞剁敓鎴?
        definition.validate();
        String legacyFormat = JsonDslParser.toJson(definition);
        List<Device> devices = JsonDslEngine.generateList(legacyFormat, Device.class);

        System.out.println("鐢熸垚鐨勫鏉傝澶囨暟閲? " + devices.size());
        devices.forEach(device -> {
            System.out.println("  - " + device.getDeviceId() + " (" + device.getStatus() + ", " + device.getDeviceGroupId() + ")");
            System.out.println("    鍦ㄧ嚎绛栫暐: " + device.getOnlineStrategy());
        });
        System.out.println();
    }

    /**
     * 绀轰緥3: 杩囨护鍣?DSL
     */
    private static void example3_FilterDsl() {
        System.out.println("--- 绀轰緥3: 杩囨护鍣?DSL ---");

        // 1. 鍒涘缓杩囨护鍣?DSL 瀹氫箟
        JsonDslDefinition filterDef = new JsonDslDefinition("online_device_filter", JsonDslDefinition.DslType.FILTER);
        filterDef.setDescription("杩囨护鍦ㄧ嚎璁惧");
        filterDef.setAuthor("system");
        filterDef.setPriority(10);

        // 2. 璁剧疆瀛楁杩囨护鏉′欢
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("status", Map.of("$eq", "ONLINE"));
        fieldDsl.put("deviceGroupId", Map.of("$in", Arrays.asList("us", "gb")));
        filterDef.setFieldDsl(fieldDsl);

        // 3. 璁剧疆缁勫悎杩囨护鏉′欢
        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("battery_check", "batteryLevel >= 20");
        combineDsl.put("signal_check", "signalStrength >= 50");
        filterDef.setCombineDsl(combineDsl);

        // 4. 楠岃瘉杩囨护鍣?
        filterDef.validate();

        // 5. 杞崲涓轰紶缁熸牸寮?
        String filterConfig = JsonDslParser.toJson(filterDef);
        System.out.println("杩囨护鍣ㄩ厤缃? " + filterConfig);

        // 6. 搴旂敤杩囨护鍣紙闇€瑕佸厛鏈夋暟鎹級
        // List<Object> filtered = JsonDslEngine.filter(devices, filterConfig);
        System.out.println("杩囨护鍣?DSL 鍒涘缓鎴愬姛");
        System.out.println();
    }

    /**
     * 绀轰緥4: 杞崲 DSL
     */
    private static void example4_TransformDsl() {
        System.out.println("--- 绀轰緥4: 杞崲 DSL ---");

        // 1. 鍒涘缓杞崲 DSL 瀹氫箟
        JsonDslDefinition transformDef = new JsonDslDefinition("device_transformer", JsonDslDefinition.DslType.TRANSFORM);
        transformDef.setDescription("杞崲璁惧鏁版嵁鏍煎紡");
        transformDef.setAuthor("system");
        transformDef.setPriority(5);

        // 2. 璁剧疆杞崲瑙勫垯
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("deviceId", Map.of("$UPPER", "&.deviceId"));
        fieldDsl.put("status", Map.of("$MAP", Map.of("ONLINE", "active", "OFFLINE", "inactive")));
        fieldDsl.put("deviceGroupId", Map.of("$UPPER", "&.deviceGroupId"));
        transformDef.setFieldDsl(fieldDsl);

        // 3. 璁剧疆缁勫悎杞崲瑙勫垯
        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("full_name", "deviceId + '_' + deviceGroupId");
        combineDsl.put("status_code", "status == 'active' ? 1 : 0");
        transformDef.setCombineDsl(combineDsl);

        // 4. 楠岃瘉杞崲鍣?
        transformDef.validate();
        System.out.println("杞崲 DSL 鍒涘缓鎴愬姛");
        System.out.println();
    }

    /**
     * 绀轰緥5: 楠岃瘉 DSL
     */
    private static void example5_ValidateDsl() {
        System.out.println("--- 绀轰緥5: 楠岃瘉 DSL ---");

        // 1. 鍒涘缓楠岃瘉 DSL 瀹氫箟
        JsonDslDefinition validateDef = new JsonDslDefinition("device_validator", JsonDslDefinition.DslType.VALIDATE);
        validateDef.setDescription("楠岃瘉璁惧鏁版嵁鏈夋晥鎬?);
        validateDef.setAuthor("system");
        validateDef.setPriority(1);

        // 2. 璁剧疆楠岃瘉瑙勫垯
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("deviceId", Map.of("required", true, "pattern", "^device-\\d+$"));
        fieldDsl.put("status", Map.of("enum", Arrays.asList("ONLINE", "OFFLINE")));
        fieldDsl.put("deviceGroupId", Map.of("required", true, "minLength", 2, "maxLength", 10));
        validateDef.setFieldDsl(fieldDsl);

        // 3. 璁剧疆缁勫悎楠岃瘉瑙勫垯
        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("status_consistency", "status == 'ONLINE' ? batteryLevel > 0 : true");
        combineDsl.put("group_validity", "deviceGroupId in ['us', 'gb', 'cn', 'eu']");
        validateDef.setCombineDsl(combineDsl);

        // 4. 楠岃瘉楠岃瘉鍣?
        validateDef.validate();
        System.out.println("楠岃瘉 DSL 鍒涘缓鎴愬姛");
        System.out.println();
    }

    /**
     * 绀轰緥6: 浠?JSON 瑙ｆ瀽 DSL锛堜娇鐢ㄥ叏绫诲悕锛?
     */
    private static void example6_ParseFromJson() {
        System.out.println("--- 绀轰緥6: 浠?JSON 瑙ｆ瀽 DSL锛堜娇鐢ㄥ叏绫诲悕锛?---");

        // 1. 鏍囧噯鍖?DSL JSON
        String jsonDsl = """
                {
                  "unique_id": "json_device_generator",
                  "type": "generate",
                  "priority": 1,
                  "desc": "浠?JSON 瑙ｆ瀽鐨勮澶囩敓鎴愬櫒",
                  "version": "1.0",
                  "author": "json_user",
                  "tags": ["json", "device"],
                  "context": {
                    "MODEL": "com.xa.mass.base.model.Device",
                    "COUNT": 2,
                    "scope_name": "Device",
                    "debug": true
                  },
                  "fieldDsl": {
                    "deviceId": {"$JOIN": ["json-device-", "&.index"]},
                    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
                    "deviceGroupId": {"$CHOICE": ["us", "gb"]},
                    "createdTime": {
                      "$EXPR": {
                        "lang": "ql",
                        "expr": "now('yyyy-MM-dd HH:mm:ss')"
                      }
                    }
                  },
                  "combine_dsl": {
                    "status_group": "status == 'ONLINE' ? deviceGroupId : 'unknown'"
                  },
                  "extensions": {
                    "source": "json_parser"
                  }
                }
                """;

        // 2. 瑙ｆ瀽 JSON
        JsonDslDefinition definition = JsonDslParser.parse(jsonDsl);

        // 3. 楠岃瘉瑙ｆ瀽缁撴灉
        System.out.println("瑙ｆ瀽鐨?DSL ID: " + definition.getUniqueId());
        System.out.println("DSL 绫诲瀷: " + definition.getType());
        System.out.println("鎻忚堪: " + definition.getDescription());
        System.out.println("浣滆€? " + definition.getAuthor());
        System.out.println("鏍囩: " + Arrays.toString(definition.getTags()));

        // 4. 杞崲涓轰紶缁熸牸寮忓苟鐢熸垚鏁版嵁
        String legacyFormat = JsonDslParser.toJson(definition);
        List<Device> devices = JsonDslEngine.generateList(legacyFormat, Device.class);

        System.out.println("浠?JSON 鐢熸垚鐨勮澶囨暟閲? " + devices.size());
        devices.forEach(device ->
                System.out.println("  - " + device.getDeviceId() + " (" + device.getStatus() + ", " + device.getDeviceGroupId() + ")")
        );
        System.out.println();
    }
} 
