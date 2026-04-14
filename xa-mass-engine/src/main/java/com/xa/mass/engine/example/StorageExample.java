package com.xa.mass.engine.example;

import com.xa.mass.base.enums.Project;
import com.xa.mass.base.enums.task.TokenStatus;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.listener.SimpleTaskMsgAssignListener;
import com.xa.mass.engine.listener.TaskDeviceAssignListener;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.monkey.MonkeyGenerator;
import com.xa.mass.engine.rules.RuleManagerFactory;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.storage.*;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;
import com.xa.mass.engine.strategy.TaskScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 瀛樺偍绯荤粺浣跨敤绀轰緥
 * 灞曠ず濡備綍浣跨敤涓嶅悓鐨勫瓨鍌ㄥ疄鐜帮紙鍐呭瓨銆丷edis绛夛級
 */
public class StorageExample {

    private static final Logger log = LoggerFactory.getLogger(StorageExample.class);

    public static void main(String[] args) {
        // 娴嬭瘯鍩烘湰瀛樺偍鍔熻兘
        testBasicStorage();

        // 娴嬭瘯浠诲姟鍒嗛厤鍔熻兘
        testTaskAssignment();

        // 娴嬭瘯 MockTaskEngineSpringBootApp 榛樿閰嶇疆
        testMockAppDefaultConfig();

        // 娴嬭瘯 MockTaskEngineSpringBootApp 瀹為檯杩愯鎯呭喌
        testMockAppActualRun();
    }

    /**
     * 娴嬭瘯鍩烘湰瀛樺偍鍔熻兘
     */
    public static void testBasicStorage() {
        log.info("=== 娴嬭瘯鍩烘湰瀛樺偍鍔熻兘 ===");

        // 1. 鍒涘缓瀛樺偍瀹炰緥
        DeviceStorage deviceStorage = new InMemoryDeviceStorage();
        TaskStorage taskStorage = new InMemoryTaskStorage();
        RuleStorage ruleStorage = new InMemoryRuleStorage();

        // 2. 鍒涘缓绠＄悊鍣?
        DeviceManager deviceManager = new DeviceManager(deviceStorage);
        TaskScheduler taskScheduler = new SimpleTaskScheduler();
        TaskManager taskManager = new TaskManager(taskScheduler, taskStorage);

        // 3. 娣诲姞娴嬭瘯鏁版嵁
        Device device1 = new Device("device-001", "1.0.0", Arrays.asList(Project.DEMO_APP));
        device1.setDeviceGroupId("us");
        Device device2 = new Device("device-002", "1.0.1", Arrays.asList(Project.DEMO_APP));
        device2.setDeviceGroupId("gb");

        Token token1 = new Token("token-001", "device-001", "us");
        token1.setStatus(TokenStatus.LOGIN_READY);
        Token token2 = new Token("token-002", "device-002", "gb");
        token2.setStatus(TokenStatus.LOGIN_READY);

        deviceManager.addDevice(device1);
        deviceManager.addDevice(device2);
        deviceManager.addToken("device-001", token1);
        deviceManager.addToken("device-002", token2);

        // 4. 楠岃瘉鏁版嵁
        List<Device> allDevices = deviceManager.getAllDevices();
        List<Device> usDevices = deviceManager.getDevicesByGroupId("us");
        List<Device> gbDevices = deviceManager.getDevicesByGroupId("gb");

        log.info("鎵€鏈夎澶? {}", allDevices.size());
        log.info("US璁惧: {}", usDevices.size());
        log.info("GB璁惧: {}", gbDevices.size());

        Token retrievedToken1 = deviceManager.getToken("device-001");
        Token retrievedToken2 = deviceManager.getToken("device-002");

        log.info("Token1: {}", retrievedToken1 != null ? retrievedToken1.getTokenId() : "null");
        log.info("Token2: {}", retrievedToken2 != null ? retrievedToken2.getTokenId() : "null");

        log.info("=== 鍩烘湰瀛樺偍鍔熻兘娴嬭瘯瀹屾垚 ===");
    }

    /**
     * 娴嬭瘯浠诲姟鍒嗛厤鍔熻兘
     */
    public static void testTaskAssignment() {
        log.info("=== 娴嬭瘯浠诲姟鍒嗛厤鍔熻兘 ===");

        // 1. 浣跨敤mock_config.json閰嶇疆鐢熸垚璁惧
        String mockConfig = "{\n" +
                "  \"devices\": [\n" +
                "    {\n" +
                "      \"deviceIdTemplate\": \"device-{i}\",\n" +
                "      \"count\": 100,\n" +
                "      \"deviceGroupId\": \"us\",\n" +
                "      \"agentVersion\": \"1.0.0\",\n" +
                "      \"supportedApps\": [\"demoApp\", \"otherApp\", \"testApp\"],\n" +
                "      \"tokens\": [\n" +
                "        {\n" +
                "          \"tokenIdTemplate\": \"token-{i}-A\",\n" +
                "          \"count\": 20,\n" +
                "          \"channel\": \"us\",\n" +
                "          \"randomStatus\": false\n" +
                "        },\n" +
                "        {\n" +
                "          \"tokenIdTemplate\": \"token-{i}-B\",\n" +
                "          \"count\": 10,\n" +
                "          \"channel\": \"us\",\n" +
                "          \"randomStatus\": false\n" +
                "        }\n" +
                "      ]\n" +
                "    },\n" +
                "    {\n" +
                "      \"deviceIdTemplate\": \"gb-device-{i}\",\n" +
                "      \"count\": 50,\n" +
                "      \"deviceGroupId\": \"gb\",\n" +
                "      \"agentVersion\": \"1.0.1\",\n" +
                "      \"supportedApps\": [\"demoApp\", \"testApp\"],\n" +
                "      \"tokens\": [\n" +
                "        {\n" +
                "          \"tokenIdTemplate\": \"gb-token-{i}-{j}\",\n" +
                "          \"count\": 1,\n" +
                "          \"channel\": \"gb\",\n" +
                "          \"randomStatus\": false\n" +
                "        }\n" +
                "      ]\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(mockConfig).getAsJsonObject();
        List<Token> tokenList = new ArrayList<>();
        List<Device> devices = MonkeyGenerator.generateDevices(root.getAsJsonArray("devices").toString());

        log.info("鐢熸垚浜?{} 涓澶囧拰 {} 涓猅oken", devices.size(), tokenList.size());

        // 2. 缁熻Token鐘舵€?
        long loginReadyCount = tokenList.stream()
                .filter(token -> token.getStatus() == TokenStatus.LOGIN_READY)
                .count();
        long invalidCount = tokenList.stream()
                .filter(token -> token.getStatus() == TokenStatus.INVALID)
                .count();

        log.info("Token鐘舵€佺粺璁?- LOGIN_READY: {}, INVALID: {}", loginReadyCount, invalidCount);

        // 3. 缁熻璁惧鍒嗙粍
        long usCount = devices.stream()
                .filter(device -> "us".equals(device.getDeviceGroupId()))
                .count();
        long gbCount = devices.stream()
                .filter(device -> "gb".equals(device.getDeviceGroupId()))
                .count();

        log.info("璁惧鍒嗙粍缁熻 - US: {}, GB: {}", usCount, gbCount);

        // 4. 鍒涘缓璁惧绠＄悊鍣ㄥ苟娣诲姞璁惧
        var deviceManager = new DeviceManager();
        for (Device device : devices) {
            deviceManager.addDevice(device);
        }
        for (Token token : tokenList) {
            deviceManager.addToken(token.getDeviceId(), token);
        }

        // 5. 鐢熸垚娴嬭瘯浠诲姟
        String taskJson = MonkeyGenerator.exampleTasksJsonDsl();
        log.info("浠诲姟鐢熸垚JSON: {}", taskJson);

        com.google.gson.JsonArray taskArray = com.google.gson.JsonParser.parseString(taskJson).getAsJsonArray();
        List<TaskCreateRequestDto> taskDtos = MonkeyGenerator.generateTasks(taskArray.toString());
        log.info("鐢熸垚浜?{} 涓换鍔?, taskDtos.size());

        // 6. 娴嬭瘯瑙勫垯鍖归厤
        var ruleManager = RuleManagerFactory.getProjectRuleManager("demoApp");
        var recordService = new AssignmentRecordService();
        var taskManager = new TaskManager(new SimpleTaskScheduler(), new InMemoryTaskStorage());
        var msgAssignListener = new SimpleTaskMsgAssignListener(taskManager, deviceManager, recordService);
        var deviceAssignListener = new TaskDeviceAssignListener(ruleManager, deviceManager, msgAssignListener, recordService, taskManager);

        // 鏄剧ず瑙勫垯淇℃伅
        var rules = ruleManager.getDefaultRules();
        log.info("瑙勫垯鏁伴噺: {}", rules.size());
        for (var rule : rules) {
            log.info("瑙勫垯: {} - {}", rule.getId(), rule.getContent());
        }

        // 7. 娴嬭瘯浠诲姟鍒嗛厤
        for (TaskCreateRequestDto dto : taskDtos) {
            log.info("娴嬭瘯浠诲姟: {} (country: {}, project: {})", dto.getTaskName(), dto.getCountryCode(), dto.getProject());

            // 鑾峰彇鍊欓€夎澶?
            List<Device> candidates = deviceManager.getDevicesByGroupId(dto.getCountryCode());
            log.info("鍊欓€夎澶囨暟閲? {}", candidates.size());

            // 妯℃嫙 TaskDeviceAssignListener 鐨勫尮閰嶉€昏緫
            List<Device> matchedDevices = new ArrayList<>();
            for (Device device : candidates) {
                Token token = deviceManager.getToken(device.getDeviceId());
                if (token != null) {
                    // 妫€鏌oken鐘舵€?
                    boolean tokenAllocatable = token.isAllocatable();
                    boolean tokenAvailable = token.isAvailable();

                    if (tokenAllocatable && tokenAvailable) {
                        // 灏濊瘯閿佸畾璁惧
                        if (deviceManager.tryLockDevice(device.getDeviceId())) {
                            matchedDevices.add(device);
                            log.info("鉁?璁惧鍖归厤鎴愬姛: {} (token: {}, status: {})",
                                    device.getDeviceId(), token.getTokenId(), token.getStatus());
                        } else {
                            log.info("鉂?璁惧閿佸畾澶辫触: {} (鍙兘宸茶鍏朵粬浠诲姟鍗犵敤)", device.getDeviceId());
                        }
                    } else {
                        log.debug("鉂?璁惧涓嶅尮閰? {} (token: {}, allocatable: {}, available: {})",
                                device.getDeviceId(), token.getTokenId(), tokenAllocatable, tokenAvailable);
                    }
                } else {
                    log.debug("鉂?璁惧鏃燭oken: {}", device.getDeviceId());
                }
            }

            log.info("浠诲姟 {} 鍖归厤璁惧鏁伴噺: {}", dto.getTaskName(), matchedDevices.size());

            // 瑙ｉ攣璁惧锛屼负涓嬩竴涓换鍔″仛鍑嗗
            for (Device device : matchedDevices) {
                deviceManager.unlockDevice(device.getDeviceId());
            }
        }

        log.info("=== 浠诲姟鍒嗛厤鍔熻兘娴嬭瘯瀹屾垚 ===");
    }

    /**
     * 娴嬭瘯 MockTaskEngineSpringBootApp 鐨勯粯璁ら厤缃?
     */
    public static void testMockAppDefaultConfig() {
        log.info("=== 娴嬭瘯 MockTaskEngineSpringBootApp 榛樿閰嶇疆 ===");

        // 1. 浣跨敤榛樿閰嶇疆鐢熸垚璁惧
        String defaultConfig = MonkeyGenerator.exampleJsonDsl();
        log.info("榛樿閰嶇疆: {}", defaultConfig);

        List<Token> tokenList = new ArrayList<>();
        List<Device> devices = MonkeyGenerator.generateDevices(defaultConfig);

        log.info("鐢熸垚浜?{} 涓澶囧拰 {} 涓猅oken", devices.size(), tokenList.size());

        // 2. 缁熻Token鐘舵€?
        long loginReadyCount = tokenList.stream()
                .filter(token -> token.getStatus() == TokenStatus.LOGIN_READY)
                .count();
        long invalidCount = tokenList.stream()
                .filter(token -> token.getStatus() == TokenStatus.INVALID)
                .count();

        log.info("Token鐘舵€佺粺璁?- LOGIN_READY: {}, INVALID: {}", loginReadyCount, invalidCount);

        // 3. 缁熻璁惧鍒嗙粍
        long usCount = devices.stream()
                .filter(device -> "us".equals(device.getDeviceGroupId()))
                .count();
        long gbCount = devices.stream()
                .filter(device -> "gb".equals(device.getDeviceGroupId()))
                .count();

        log.info("璁惧鍒嗙粍缁熻 - US: {}, GB: {}", usCount, gbCount);

        // 4. 鍒涘缓璁惧绠＄悊鍣ㄥ苟娣诲姞璁惧
        var deviceManager = new DeviceManager();
        for (Device device : devices) {
            deviceManager.addDevice(device);
        }
        for (Token token : tokenList) {
            deviceManager.addToken(token.getDeviceId(), token);
        }

        // 5. 鐢熸垚娴嬭瘯浠诲姟
        String taskJson = MonkeyGenerator.exampleTasksJsonDsl();
        com.google.gson.JsonArray taskArray = com.google.gson.JsonParser.parseString(taskJson).getAsJsonArray();
        List<TaskCreateRequestDto> taskDtos = MonkeyGenerator.generateTasks(taskArray.toString());

        log.info("鐢熸垚浜?{} 涓换鍔?, taskDtos.size());

        // 6. 娴嬭瘯浠诲姟鍒嗛厤
        for (TaskCreateRequestDto dto : taskDtos) {
            log.info("娴嬭瘯浠诲姟: {} (country: {}, project: {})", dto.getTaskName(), dto.getCountryCode(), dto.getProject());

            // 鑾峰彇鍊欓€夎澶?
            List<Device> candidates = deviceManager.getDevicesByGroupId(dto.getCountryCode());
            log.info("鍊欓€夎澶囨暟閲? {}", candidates.size());

            // 妯℃嫙 TaskDeviceAssignListener 鐨勫尮閰嶉€昏緫
            List<Device> matchedDevices = new ArrayList<>();
            for (Device device : candidates) {
                Token token = deviceManager.getToken(device.getDeviceId());
                if (token != null) {
                    // 妫€鏌oken鐘舵€?
                    boolean tokenAllocatable = token.isAllocatable();
                    boolean tokenAvailable = token.isAvailable();

                    if (tokenAllocatable && tokenAvailable) {
                        // 灏濊瘯閿佸畾璁惧
                        if (deviceManager.tryLockDevice(device.getDeviceId())) {
                            matchedDevices.add(device);
                            log.info("鉁?璁惧鍖归厤鎴愬姛: {} (token: {}, status: {})",
                                    device.getDeviceId(), token.getTokenId(), token.getStatus());
                        } else {
                            log.info("鉂?璁惧閿佸畾澶辫触: {} (鍙兘宸茶鍏朵粬浠诲姟鍗犵敤)", device.getDeviceId());
                        }
                    } else {
                        log.debug("鉂?璁惧涓嶅尮閰? {} (token: {}, allocatable: {}, available: {})",
                                device.getDeviceId(), token.getTokenId(), tokenAllocatable, tokenAvailable);
                    }
                } else {
                    log.debug("鉂?璁惧鏃燭oken: {}", device.getDeviceId());
                }
            }

            log.info("浠诲姟 {} 鍖归厤璁惧鏁伴噺: {}", dto.getTaskName(), matchedDevices.size());

            // 瑙ｉ攣璁惧锛屼负涓嬩竴涓换鍔″仛鍑嗗
            for (Device device : matchedDevices) {
                deviceManager.unlockDevice(device.getDeviceId());
            }
        }

        log.info("=== 娴嬭瘯瀹屾垚 ===");
    }

    /**
     * 娴嬭瘯 MockTaskEngineSpringBootApp 鐨勫疄闄呰繍琛屾儏鍐?
     */
    public static void testMockAppActualRun() {
        log.info("=== 娴嬭瘯 MockTaskEngineSpringBootApp 瀹為檯杩愯鎯呭喌 ===");

        // 1. 妯℃嫙 MassEngine 鐨勫惎鍔ㄨ繃绋?
        var deviceManager = new DeviceManager();
        var ruleManager = RuleManagerFactory.getProjectRuleManager("demoApp");
        var recordService = new AssignmentRecordService();
        var taskManager = new TaskManager(new SimpleTaskScheduler(), new InMemoryTaskStorage());
        var msgAssignListener = new SimpleTaskMsgAssignListener(taskManager, deviceManager, recordService);
        var deviceAssignListener = new TaskDeviceAssignListener(ruleManager, deviceManager, msgAssignListener, recordService, taskManager);

        // 2. 浣跨敤榛樿閰嶇疆鐢熸垚璁惧鍜孴oken
        String defaultConfig = MonkeyGenerator.exampleJsonDsl();
        List<Token> tokenList = new ArrayList<>();
        List<Device> devices = MonkeyGenerator.generateDevices(defaultConfig);

        log.info("鐢熸垚浜?{} 涓澶囧拰 {} 涓猅oken", devices.size(), tokenList.size());

        // 3. 娣诲姞璁惧鍜孴oken鍒扮鐞嗗櫒
        for (Device device : devices) {
            deviceManager.addDevice(device);
        }
        for (Token token : tokenList) {
            deviceManager.addToken(token.getDeviceId(), token);
        }

        // 4. 楠岃瘉璁惧鍜孴oken娣诲姞鎯呭喌
        List<Device> allDevices = deviceManager.getAllDevices();
        List<Device> usDevices = deviceManager.getDevicesByGroupId("us");
        List<Device> gbDevices = deviceManager.getDevicesByGroupId("gb");

        log.info("璁惧楠岃瘉 - 鎬绘暟: {}, US: {}, GB: {}", allDevices.size(), usDevices.size(), gbDevices.size());

        // 5. 鏄剧ず鍓嶅嚑涓澶囩殑璇︾粏淇℃伅
        for (int i = 0; i < Math.min(3, allDevices.size()); i++) {
            Device device = allDevices.get(i);
            Token token = deviceManager.getToken(device.getDeviceId());
            log.info("璁惧 {}: id={}, deviceGroupId={}, status={}, token={}, tokenStatus={}",
                    i + 1, device.getDeviceId(), device.getDeviceGroupId(), device.getStatus(),
                    token != null ? token.getTokenId() : "null",
                    token != null ? token.getStatus() : "null");
        }

        // 6. 鐢熸垚娴嬭瘯浠诲姟
        String taskJson = MonkeyGenerator.exampleTasksJsonDsl();
        com.google.gson.JsonArray taskArray = com.google.gson.JsonParser.parseString(taskJson).getAsJsonArray();
        List<TaskCreateRequestDto> taskDtos = MonkeyGenerator.generateTasks(taskArray.toString());

        log.info("鐢熸垚浜?{} 涓换鍔?, taskDtos.size());

        // 7. 娴嬭瘯浠诲姟鍒嗛厤锛屾ā鎷熷疄闄呰繍琛岃繃绋?
        for (TaskCreateRequestDto dto : taskDtos) {
            log.info("娴嬭瘯浠诲姟: {} (country: {}, project: {})", dto.getTaskName(), dto.getCountryCode(), dto.getProject());

            // 鑾峰彇鍊欓€夎澶?
            List<Device> candidates = deviceManager.getDevicesByGroupId(dto.getCountryCode());
            log.info("鍊欓€夎澶囨暟閲? {}", candidates.size());

            // 妯℃嫙 TaskDeviceAssignListener 鐨勫尮閰嶉€昏緫
            List<Device> matchedDevices = new ArrayList<>();
            for (Device device : candidates) {
                Token token = deviceManager.getToken(device.getDeviceId());
                if (token != null) {
                    // 妫€鏌oken鐘舵€?
                    boolean tokenAllocatable = token.isAllocatable();
                    boolean tokenAvailable = token.isAvailable();

                    log.debug("璁惧 {}: token={}, status={}, allocatable={}, available={}",
                            device.getDeviceId(), token.getTokenId(), token.getStatus(), tokenAllocatable, tokenAvailable);

                    if (tokenAllocatable && tokenAvailable) {
                        // 灏濊瘯閿佸畾璁惧
                        if (deviceManager.tryLockDevice(device.getDeviceId())) {
                            matchedDevices.add(device);
                            log.info("鉁?璁惧鍖归厤鎴愬姛: {} (token: {}, status: {})",
                                    device.getDeviceId(), token.getTokenId(), token.getStatus());
                        } else {
                            log.info("鉂?璁惧閿佸畾澶辫触: {} (鍙兘宸茶鍏朵粬浠诲姟鍗犵敤)", device.getDeviceId());
                        }
                    } else {
                        log.debug("鉂?璁惧涓嶅尮閰? {} (token: {}, allocatable: {}, available: {})",
                                device.getDeviceId(), token.getTokenId(), tokenAllocatable, tokenAvailable);
                    }
                } else {
                    log.debug("鉂?璁惧鏃燭oken: {}", device.getDeviceId());
                }
            }

            log.info("浠诲姟 {} 鍖归厤璁惧鏁伴噺: {}", dto.getTaskName(), matchedDevices.size());

            // 瑙ｉ攣璁惧锛屼负涓嬩竴涓换鍔″仛鍑嗗
            for (Device device : matchedDevices) {
                deviceManager.unlockDevice(device.getDeviceId());
            }
        }

        log.info("=== 娴嬭瘯瀹屾垚 ===");
    }
} 
