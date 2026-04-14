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
 * 鐎涙ê鍋嶇化鑽ょ埠娴ｈ法鏁ょ粈杞扮伐
 * 鐏炴洜銇氭俊鍌欑秿娴ｈ法鏁ゆ稉宥呮倱閻ㄥ嫬鐡ㄩ崒銊ョ杽閻滃府绱欓崘鍛摠閵嗕阜edis缁涘绱?
 */
public class StorageExample {

    private static final Logger log = LoggerFactory.getLogger(StorageExample.class);

    public static void main(String[] args) {
        // 濞村鐦崺鐑樻拱鐎涙ê鍋嶉崝鐔诲厴
        testBasicStorage();

        // 濞村鐦禒璇插閸掑棝鍘ら崝鐔诲厴
        testTaskAssignment();

        // 濞村鐦?MockTaskEngineSpringBootApp 姒涙顓婚柊宥囩枂
        testMockAppDefaultConfig();

        // 濞村鐦?MockTaskEngineSpringBootApp 鐎圭偤妾潻鎰攽閹懎鍠?
        testMockAppActualRun();
    }

    /**
     * 濞村鐦崺鐑樻拱鐎涙ê鍋嶉崝鐔诲厴
     */
    public static void testBasicStorage() {
        log.info("=== 濞村鐦崺鐑樻拱鐎涙ê鍋嶉崝鐔诲厴 ===");

        // 1. 閸掓稑缂撶€涙ê鍋嶇€圭偘绶?
        DeviceStorage deviceStorage = new InMemoryDeviceStorage();
        TaskStorage taskStorage = new InMemoryTaskStorage();
        RuleStorage ruleStorage = new InMemoryRuleStorage();

        // 2. 閸掓稑缂撶粻锛勬倞閸?
        DeviceManager deviceManager = new DeviceManager(deviceStorage);
        TaskScheduler taskScheduler = new SimpleTaskScheduler();
        TaskManager taskManager = new TaskManager(taskScheduler, taskStorage);

        // 3. 濞ｈ濮炲ù瀣槸閺佺増宓?
        Device device1 = new Device("device-001", "1.0.0", Arrays.asList(Project.DEMO_APP));
        device1.setDeviceGroupId("us");
        Device device2 = new Device("device-002", "1.0.1", Arrays.asList(Project.DEMO_APP));
        device2.setDeviceGroupId("gb");

        Token token1 = new Token("token-001", "device-001", "us");
        token1.setStatus(TokenStatus.IDLE);
        Token token2 = new Token("token-002", "device-002", "gb");
        token2.setStatus(TokenStatus.IDLE);

        deviceManager.addDevice(device1);
        deviceManager.addDevice(device2);
        deviceManager.addToken("device-001", token1);
        deviceManager.addToken("device-002", token2);

        // 4. 妤犲矁鐦夐弫鐗堝祦
        List<Device> allDevices = deviceManager.getAllDevices();
        List<Device> usDevices = deviceManager.getDevicesByGroupId("us");
        List<Device> gbDevices = deviceManager.getDevicesByGroupId("gb");

        log.info("閹碘偓閺堝顔曟径? {}", allDevices.size());
        log.info("US鐠佹儳顦? {}", usDevices.size());
        log.info("GB鐠佹儳顦? {}", gbDevices.size());

        Token retrievedToken1 = deviceManager.getToken("device-001");
        Token retrievedToken2 = deviceManager.getToken("device-002");

        log.info("Token1: {}", retrievedToken1 != null ? retrievedToken1.getTokenId() : "null");
        log.info("Token2: {}", retrievedToken2 != null ? retrievedToken2.getTokenId() : "null");

        log.info("=== 閸╃儤婀扮€涙ê鍋嶉崝鐔诲厴濞村鐦€瑰本鍨?===");
    }

    /**
     * 濞村鐦禒璇插閸掑棝鍘ら崝鐔诲厴
     */
    public static void testTaskAssignment() {
        log.info("=== 濞村鐦禒璇插閸掑棝鍘ら崝鐔诲厴 ===");

        // 1. 娴ｈ法鏁ock_config.json闁板秶鐤嗛悽鐔稿灇鐠佹儳顦?
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

        log.info("閻㈢喐鍨氭禍?{} 娑擃亣顔曟径鍥ф嫲 {} 娑撶寘oken", devices.size(), tokenList.size());

        // 2. 缂佺喕顓窽oken閻樿埖鈧?
        long loginReadyCount = tokenList.stream()
                .filter(token -> token.getStatus() == TokenStatus.IDLE)
                .count();
        long invalidCount = tokenList.stream()
                .filter(token -> token.getStatus() == TokenStatus.INVALID)
                .count();

        log.info("Token閻樿埖鈧胶绮虹拋?- LOGIN_READY: {}, INVALID: {}", loginReadyCount, invalidCount);

        // 3. 缂佺喕顓哥拋鎯ь槵閸掑棛绮?
        long usCount = devices.stream()
                .filter(device -> "us".equals(device.getDeviceGroupId()))
                .count();
        long gbCount = devices.stream()
                .filter(device -> "gb".equals(device.getDeviceGroupId()))
                .count();

        log.info("鐠佹儳顦崚鍡欑矋缂佺喕顓?- US: {}, GB: {}", usCount, gbCount);

        // 4. 閸掓稑缂撶拋鎯ь槵缁狅紕鎮婇崳銊ヨ嫙濞ｈ濮炵拋鎯ь槵
        var deviceManager = new DeviceManager();
        for (Device device : devices) {
            deviceManager.addDevice(device);
        }
        for (Token token : tokenList) {
            deviceManager.addToken(token.getDeviceId(), token);
        }

        // 5. 閻㈢喐鍨氬ù瀣槸娴犺濮?
        String taskJson = MonkeyGenerator.exampleTasksJsonDsl();
        log.info("娴犺濮熼悽鐔稿灇JSON: {}", taskJson);

        com.google.gson.JsonArray taskArray = com.google.gson.JsonParser.parseString(taskJson).getAsJsonArray();
        List<TaskCreateRequestDto> taskDtos = MonkeyGenerator.generateTasks(taskArray.toString());
        log.info("Generated {} mock tasks", taskDtos.size());

        // 6. 濞村鐦憴鍕灟閸栧綊鍘?
        var ruleManager = RuleManagerFactory.getProjectRuleManager("demoApp");
        var recordService = new AssignmentRecordService();
        var taskManager = new TaskManager(new SimpleTaskScheduler(), new InMemoryTaskStorage());
        var msgAssignListener = new SimpleTaskMsgAssignListener(taskManager, deviceManager, recordService);
        var deviceAssignListener = new TaskDeviceAssignListener(ruleManager, deviceManager, msgAssignListener, recordService, taskManager);

        // 閺勫墽銇氱憴鍕灟娣団剝浼?
        var rules = ruleManager.getDefaultRules();
        log.info("鐟欏嫬鍨弫浼村櫤: {}", rules.size());
        for (var rule : rules) {
            log.info("鐟欏嫬鍨? {} - {}", rule.getId(), rule.getContent());
        }

        // 7. 濞村鐦禒璇插閸掑棝鍘?
        for (TaskCreateRequestDto dto : taskDtos) {
            log.info("濞村鐦禒璇插: {} (country: {}, project: {})", dto.getTaskName(), dto.getCountryCode(), dto.getProject());

            // 閼惧嘲褰囬崐娆撯偓澶庮啎婢?
            List<Device> candidates = deviceManager.getDevicesByGroupId(dto.getCountryCode());
            log.info("閸婃瑩鈧顔曟径鍥ㄦ殶闁? {}", candidates.size());

            // 濡剝瀚?TaskDeviceAssignListener 閻ㄥ嫬灏柊宥夆偓鏄忕帆
            List<Device> matchedDevices = new ArrayList<>();
            for (Device device : candidates) {
                Token token = deviceManager.getToken(device.getDeviceId());
                if (token != null) {
                    // 濡偓閺岊櫄oken閻樿埖鈧?
                    boolean tokenAllocatable = token.isAllocatable();
                    boolean tokenAvailable = token.isAvailable();

                    if (tokenAllocatable && tokenAvailable) {
                        // 鐏忔繆鐦柨浣哥暰鐠佹儳顦?
                        if (deviceManager.tryLockDevice(device.getDeviceId())) {
                            matchedDevices.add(device);
                            log.info("閴?鐠佹儳顦崠褰掑帳閹存劕濮? {} (token: {}, status: {})",
                                    device.getDeviceId(), token.getTokenId(), token.getStatus());
                        } else {
                            log.info("閴?鐠佹儳顦柨浣哥暰婢惰精瑙? {} (閸欘垵鍏樺鑼额潶閸忔湹绮禒璇插閸楃姷鏁?", device.getDeviceId());
                        }
                    } else {
                        log.debug("閴?鐠佹儳顦稉宥呭爱闁? {} (token: {}, allocatable: {}, available: {})",
                                device.getDeviceId(), token.getTokenId(), tokenAllocatable, tokenAvailable);
                    }
                } else {
                    log.debug("閴?鐠佹儳顦弮鐕璷ken: {}", device.getDeviceId());
                }
            }

            log.info("娴犺濮?{} 閸栧綊鍘ょ拋鎯ь槵閺佷即鍣? {}", dto.getTaskName(), matchedDevices.size());

            // 鐟欙綁鏀ｇ拋鎯ь槵閿涘奔璐熸稉瀣╃娑擃亙鎹㈤崝鈥充粵閸戝棗顦?
            for (Device device : matchedDevices) {
                deviceManager.unlockDevice(device.getDeviceId());
            }
        }

        log.info("=== 娴犺濮熼崚鍡涘帳閸旂喕鍏樺ù瀣槸鐎瑰本鍨?===");
    }

    /**
     * 濞村鐦?MockTaskEngineSpringBootApp 閻ㄥ嫰绮拋銈夊帳缂?
     */
    public static void testMockAppDefaultConfig() {
        log.info("=== 濞村鐦?MockTaskEngineSpringBootApp 姒涙顓婚柊宥囩枂 ===");

        // 1. 娴ｈ法鏁ゆ妯款吇闁板秶鐤嗛悽鐔稿灇鐠佹儳顦?
        String defaultConfig = MonkeyGenerator.exampleJsonDsl();
        log.info("姒涙顓婚柊宥囩枂: {}", defaultConfig);

        List<Token> tokenList = new ArrayList<>();
        List<Device> devices = MonkeyGenerator.generateDevices(defaultConfig);

        log.info("閻㈢喐鍨氭禍?{} 娑擃亣顔曟径鍥ф嫲 {} 娑撶寘oken", devices.size(), tokenList.size());

        // 2. 缂佺喕顓窽oken閻樿埖鈧?
        long loginReadyCount = tokenList.stream()
                .filter(token -> token.getStatus() == TokenStatus.IDLE)
                .count();
        long invalidCount = tokenList.stream()
                .filter(token -> token.getStatus() == TokenStatus.INVALID)
                .count();

        log.info("Token閻樿埖鈧胶绮虹拋?- LOGIN_READY: {}, INVALID: {}", loginReadyCount, invalidCount);

        // 3. 缂佺喕顓哥拋鎯ь槵閸掑棛绮?
        long usCount = devices.stream()
                .filter(device -> "us".equals(device.getDeviceGroupId()))
                .count();
        long gbCount = devices.stream()
                .filter(device -> "gb".equals(device.getDeviceGroupId()))
                .count();

        log.info("鐠佹儳顦崚鍡欑矋缂佺喕顓?- US: {}, GB: {}", usCount, gbCount);

        // 4. 閸掓稑缂撶拋鎯ь槵缁狅紕鎮婇崳銊ヨ嫙濞ｈ濮炵拋鎯ь槵
        var deviceManager = new DeviceManager();
        for (Device device : devices) {
            deviceManager.addDevice(device);
        }
        for (Token token : tokenList) {
            deviceManager.addToken(token.getDeviceId(), token);
        }

        // 5. 閻㈢喐鍨氬ù瀣槸娴犺濮?
        String taskJson = MonkeyGenerator.exampleTasksJsonDsl();
        com.google.gson.JsonArray taskArray = com.google.gson.JsonParser.parseString(taskJson).getAsJsonArray();
        List<TaskCreateRequestDto> taskDtos = MonkeyGenerator.generateTasks(taskArray.toString());

        log.info("Generated {} mock tasks", taskDtos.size());

        // 6. 濞村鐦禒璇插閸掑棝鍘?
        for (TaskCreateRequestDto dto : taskDtos) {
            log.info("濞村鐦禒璇插: {} (country: {}, project: {})", dto.getTaskName(), dto.getCountryCode(), dto.getProject());

            // 閼惧嘲褰囬崐娆撯偓澶庮啎婢?
            List<Device> candidates = deviceManager.getDevicesByGroupId(dto.getCountryCode());
            log.info("閸婃瑩鈧顔曟径鍥ㄦ殶闁? {}", candidates.size());

            // 濡剝瀚?TaskDeviceAssignListener 閻ㄥ嫬灏柊宥夆偓鏄忕帆
            List<Device> matchedDevices = new ArrayList<>();
            for (Device device : candidates) {
                Token token = deviceManager.getToken(device.getDeviceId());
                if (token != null) {
                    // 濡偓閺岊櫄oken閻樿埖鈧?
                    boolean tokenAllocatable = token.isAllocatable();
                    boolean tokenAvailable = token.isAvailable();

                    if (tokenAllocatable && tokenAvailable) {
                        // 鐏忔繆鐦柨浣哥暰鐠佹儳顦?
                        if (deviceManager.tryLockDevice(device.getDeviceId())) {
                            matchedDevices.add(device);
                            log.info("閴?鐠佹儳顦崠褰掑帳閹存劕濮? {} (token: {}, status: {})",
                                    device.getDeviceId(), token.getTokenId(), token.getStatus());
                        } else {
                            log.info("閴?鐠佹儳顦柨浣哥暰婢惰精瑙? {} (閸欘垵鍏樺鑼额潶閸忔湹绮禒璇插閸楃姷鏁?", device.getDeviceId());
                        }
                    } else {
                        log.debug("閴?鐠佹儳顦稉宥呭爱闁? {} (token: {}, allocatable: {}, available: {})",
                                device.getDeviceId(), token.getTokenId(), tokenAllocatable, tokenAvailable);
                    }
                } else {
                    log.debug("閴?鐠佹儳顦弮鐕璷ken: {}", device.getDeviceId());
                }
            }

            log.info("娴犺濮?{} 閸栧綊鍘ょ拋鎯ь槵閺佷即鍣? {}", dto.getTaskName(), matchedDevices.size());

            // 鐟欙綁鏀ｇ拋鎯ь槵閿涘奔璐熸稉瀣╃娑擃亙鎹㈤崝鈥充粵閸戝棗顦?
            for (Device device : matchedDevices) {
                deviceManager.unlockDevice(device.getDeviceId());
            }
        }

        log.info("=== 濞村鐦€瑰本鍨?===");
    }

    /**
     * 濞村鐦?MockTaskEngineSpringBootApp 閻ㄥ嫬鐤勯梽鍛扮箥鐞涘本鍎忛崘?
     */
    public static void testMockAppActualRun() {
        log.info("=== 濞村鐦?MockTaskEngineSpringBootApp 鐎圭偤妾潻鎰攽閹懎鍠?===");

        // 1. 濡剝瀚?MassEngine 閻ㄥ嫬鎯庨崝銊ㄧ箖缁?
        var deviceManager = new DeviceManager();
        var ruleManager = RuleManagerFactory.getProjectRuleManager("demoApp");
        var recordService = new AssignmentRecordService();
        var taskManager = new TaskManager(new SimpleTaskScheduler(), new InMemoryTaskStorage());
        var msgAssignListener = new SimpleTaskMsgAssignListener(taskManager, deviceManager, recordService);
        var deviceAssignListener = new TaskDeviceAssignListener(ruleManager, deviceManager, msgAssignListener, recordService, taskManager);

        // 2. 娴ｈ法鏁ゆ妯款吇闁板秶鐤嗛悽鐔稿灇鐠佹儳顦崪瀛磑ken
        String defaultConfig = MonkeyGenerator.exampleJsonDsl();
        List<Token> tokenList = new ArrayList<>();
        List<Device> devices = MonkeyGenerator.generateDevices(defaultConfig);

        log.info("閻㈢喐鍨氭禍?{} 娑擃亣顔曟径鍥ф嫲 {} 娑撶寘oken", devices.size(), tokenList.size());

        // 3. 濞ｈ濮炵拋鎯ь槵閸滃oken閸掓壆顓搁悶鍡楁珤
        for (Device device : devices) {
            deviceManager.addDevice(device);
        }
        for (Token token : tokenList) {
            deviceManager.addToken(token.getDeviceId(), token);
        }

        // 4. 妤犲矁鐦夌拋鎯ь槵閸滃oken濞ｈ濮為幆鍛枌
        List<Device> allDevices = deviceManager.getAllDevices();
        List<Device> usDevices = deviceManager.getDevicesByGroupId("us");
        List<Device> gbDevices = deviceManager.getDevicesByGroupId("gb");

        log.info("鐠佹儳顦宀冪槈 - 閹粯鏆? {}, US: {}, GB: {}", allDevices.size(), usDevices.size(), gbDevices.size());

        // 5. 閺勫墽銇氶崜宥呭殤娑擃亣顔曟径鍥╂畱鐠囷妇绮忔穱鈩冧紖
        for (int i = 0; i < Math.min(3, allDevices.size()); i++) {
            Device device = allDevices.get(i);
            Token token = deviceManager.getToken(device.getDeviceId());
            log.info("鐠佹儳顦?{}: id={}, deviceGroupId={}, status={}, token={}, tokenStatus={}",
                    i + 1, device.getDeviceId(), device.getDeviceGroupId(), device.getStatus(),
                    token != null ? token.getTokenId() : "null",
                    token != null ? token.getStatus() : "null");
        }

        // 6. 閻㈢喐鍨氬ù瀣槸娴犺濮?
        String taskJson = MonkeyGenerator.exampleTasksJsonDsl();
        com.google.gson.JsonArray taskArray = com.google.gson.JsonParser.parseString(taskJson).getAsJsonArray();
        List<TaskCreateRequestDto> taskDtos = MonkeyGenerator.generateTasks(taskArray.toString());

        log.info("Generated {} mock tasks", taskDtos.size());

        // 7. 濞村鐦禒璇插閸掑棝鍘ら敍灞灸侀幏鐔风杽闂勫懓绻嶇悰宀冪箖缁?
        for (TaskCreateRequestDto dto : taskDtos) {
            log.info("濞村鐦禒璇插: {} (country: {}, project: {})", dto.getTaskName(), dto.getCountryCode(), dto.getProject());

            // 閼惧嘲褰囬崐娆撯偓澶庮啎婢?
            List<Device> candidates = deviceManager.getDevicesByGroupId(dto.getCountryCode());
            log.info("閸婃瑩鈧顔曟径鍥ㄦ殶闁? {}", candidates.size());

            // 濡剝瀚?TaskDeviceAssignListener 閻ㄥ嫬灏柊宥夆偓鏄忕帆
            List<Device> matchedDevices = new ArrayList<>();
            for (Device device : candidates) {
                Token token = deviceManager.getToken(device.getDeviceId());
                if (token != null) {
                    // 濡偓閺岊櫄oken閻樿埖鈧?
                    boolean tokenAllocatable = token.isAllocatable();
                    boolean tokenAvailable = token.isAvailable();

                    log.debug("鐠佹儳顦?{}: token={}, status={}, allocatable={}, available={}",
                            device.getDeviceId(), token.getTokenId(), token.getStatus(), tokenAllocatable, tokenAvailable);

                    if (tokenAllocatable && tokenAvailable) {
                        // 鐏忔繆鐦柨浣哥暰鐠佹儳顦?
                        if (deviceManager.tryLockDevice(device.getDeviceId())) {
                            matchedDevices.add(device);
                            log.info("閴?鐠佹儳顦崠褰掑帳閹存劕濮? {} (token: {}, status: {})",
                                    device.getDeviceId(), token.getTokenId(), token.getStatus());
                        } else {
                            log.info("閴?鐠佹儳顦柨浣哥暰婢惰精瑙? {} (閸欘垵鍏樺鑼额潶閸忔湹绮禒璇插閸楃姷鏁?", device.getDeviceId());
                        }
                    } else {
                        log.debug("閴?鐠佹儳顦稉宥呭爱闁? {} (token: {}, allocatable: {}, available: {})",
                                device.getDeviceId(), token.getTokenId(), tokenAllocatable, tokenAvailable);
                    }
                } else {
                    log.debug("閴?鐠佹儳顦弮鐕璷ken: {}", device.getDeviceId());
                }
            }

            log.info("娴犺濮?{} 閸栧綊鍘ょ拋鎯ь槵閺佷即鍣? {}", dto.getTaskName(), matchedDevices.size());

            // 鐟欙綁鏀ｇ拋鎯ь槵閿涘奔璐熸稉瀣╃娑擃亙鎹㈤崝鈥充粵閸戝棗顦?
            for (Device device : matchedDevices) {
                deviceManager.unlockDevice(device.getDeviceId());
            }
        }

        log.info("=== 濞村鐦€瑰本鍨?===");
    }
} 
