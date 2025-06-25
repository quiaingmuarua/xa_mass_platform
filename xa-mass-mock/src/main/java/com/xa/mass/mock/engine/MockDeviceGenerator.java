package com.xa.mass.mock.engine;

import com.google.gson.*;
import com.xa.mass.engine.model.Device;
import com.xa.mass.engine.model.Token;
import com.xa.mass.engine.model.enums.DeviceStatus;
import com.xa.mass.engine.model.enums.TokenStatus;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 支持 JSON-DSL 批量 mock 设备/Token 的生成器。
 */
public class MockDeviceGenerator {
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{i(:(\\d+))?}|");

    /**
     * 解析 JSON-DSL，生成设备和 token 列表
     */
    public static List<Device> generateDevices(String jsonDsl, List<Token> tokenListOut) {
        List<Device> devices = new ArrayList<>();
        JsonArray arr = JsonParser.parseString(jsonDsl).getAsJsonArray();
        for (JsonElement elem : arr) {
            MockBatchConfig cfg = new Gson().fromJson(elem, MockBatchConfig.class);
            for (int i = 0; i < cfg.count; i++) {
                String deviceId = cfg.deviceIdTemplate.replace("{i}", String.valueOf(i));
                Device device = new Device();
                device.setDeviceId(deviceId);
                device.setStatus(DeviceStatus.ONLINE);
                device.setGroupId(cfg.groupId);
                device.setAgentVersion(cfg.agentVersion);
                device.setSupportedApps(new ArrayList<>(cfg.supportedApps));
                devices.add(device);
                // token
                String tokenId = cfg.tokenIdTemplate.replace("{i}", String.valueOf(i));
                Token token = new Token();
                token.setTokenId(tokenId);
                token.setDeviceId(deviceId);
                token.setChannel(cfg.groupId);
                token.setStatus(cfg.randomTokenStatus ? (ThreadLocalRandom.current().nextBoolean() ? TokenStatus.LOGIN_READY : TokenStatus.INVALID) : TokenStatus.LOGIN_READY);
                tokenListOut.add(token);
            }
        }
        return devices;
    }

    // 示例 JSON-DSL
    public static String exampleJsonDsl() {
        return "[\n" +
                "  {\n" +
                "    \"deviceIdTemplate\": \"device-{i}\",\n" +
                "    \"tokenIdTemplate\": \"token-{i}\",\n" +
                "    \"count\": 100,\n" +
                "    \"groupId\": \"us\",\n" +
                "    \"agentVersion\": \"1.0.0\",\n" +
                "    \"supportedApps\": [\"demoApp\", \"otherApp\"],\n" +
                "    \"randomTokenStatus\": true\n" +
                "  },\n" +
                "  {\n" +
                "    \"deviceIdTemplate\": \"gb-device-{i}\",\n" +
                "    \"tokenIdTemplate\": \"gb-token-{i}\",\n" +
                "    \"count\": 50,\n" +
                "    \"groupId\": \"gb\",\n" +
                "    \"agentVersion\": \"1.0.1\",\n" +
                "    \"supportedApps\": [\"demoApp\"],\n" +
                "    \"randomTokenStatus\": false\n" +
                "  }\n" +
                "]";
    }

    public static class MockBatchConfig {
        public String deviceIdTemplate = "device-{i}";
        public int count = 10;
        public String groupId = "us";
        public String agentVersion = "1.0.0";
        public List<String> supportedApps = List.of("demoApp");
        public boolean randomTokenStatus = true;
        public String tokenIdTemplate = "token-{i}";
    }
} 