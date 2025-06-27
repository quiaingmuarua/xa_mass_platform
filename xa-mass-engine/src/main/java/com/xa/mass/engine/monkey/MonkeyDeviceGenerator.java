package com.xa.mass.engine.monkey;

import com.google.gson.*;
import com.xa.mass.eventbus.enums.device.DeviceStatus;
import com.xa.mass.eventbus.enums.task.TokenStatus;
import com.xa.mass.eventbus.model.Device;
import com.xa.mass.eventbus.model.Token;


import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 支持 JSON-DSL 批量 mock 设备/Token 的生成器。
 */
public class MonkeyDeviceGenerator {
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{i(:(\\d+))?}|");

    public static class MockTokenBatchConfig {
        public String tokenIdTemplate = "token-{i}-{j}";
        public int count = 1;
        public String channel = "us";
        public boolean randomStatus = true;
    }

    public static class MockBatchConfig {
        public String deviceIdTemplate = "device-{i}";
        public int count = 10;
        public String groupId = "us";
        public String agentVersion = "1.0.0";
        public List<String> supportedApps = List.of("demoApp");
        public List<MockTokenBatchConfig> tokens = List.of();
    }

    /**
     * 解析 JSON-DSL，生成设备和 token 列表，支持 device 下多 token 批量
     */
    public static List<Device> generateDevices(String jsonDsl, List<Token> tokenListOut) {
        List<Device> devices = new ArrayList<>();
        JsonArray arr = JsonParser.parseString(jsonDsl).getAsJsonArray();
        Gson gson = new Gson();
        for (JsonElement elem : arr) {
            MockBatchConfig cfg = gson.fromJson(elem, MockBatchConfig.class);
            for (int i = 0; i < cfg.count; i++) {
                String deviceId = cfg.deviceIdTemplate.replace("{i}", String.valueOf(i));
                Device device = new Device();
                device.setDeviceId(deviceId);
                device.setStatus(DeviceStatus.ONLINE);
                device.setGroupId(cfg.groupId);
                device.setAgentVersion(cfg.agentVersion);
                device.setSupportedApps(new ArrayList<>(cfg.supportedApps));
                devices.add(device);
                // tokens
                if (cfg.tokens != null && !cfg.tokens.isEmpty()) {
                    for (MockTokenBatchConfig tokenCfg : cfg.tokens) {
                        for (int j = 0; j < tokenCfg.count; j++) {
                            String tokenId = tokenCfg.tokenIdTemplate.replace("{i}", String.valueOf(i)).replace("{j}", String.valueOf(j));
                            Token token = new Token();
                            token.setTokenId(tokenId);
                            token.setDeviceId(deviceId);
                            token.setChannel(tokenCfg.channel);
                            token.setStatus(tokenCfg.randomStatus ? (ThreadLocalRandom.current().nextBoolean() ? TokenStatus.LOGIN_READY : TokenStatus.INVALID) : TokenStatus.LOGIN_READY);
                            tokenListOut.add(token);
                        }
                    }
                } else {
                    // 兼容老格式，生成一个 token
                    String tokenId = "token-" + i;
                    Token token = new Token();
                    token.setTokenId(tokenId);
                    token.setDeviceId(deviceId);
                    token.setChannel(cfg.groupId);
                    token.setStatus(TokenStatus.LOGIN_READY);
                    tokenListOut.add(token);
                }
            }
        }
        return devices;
    }

    // 示例 JSON-DSL
    public static String exampleJsonDsl() {
        return "[\n" +
                "  {\n" +
                "    \"deviceIdTemplate\": \"device-{i}\",\n" +
                "    \"count\": 100,\n" +
                "    \"groupId\": \"us\",\n" +
                "    \"agentVersion\": \"1.0.0\",\n" +
                "    \"supportedApps\": [\"demoApp\", \"otherApp\"],\n" +
                "    \"tokens\": [\n" +
                "      {\n" +
                "        \"tokenIdTemplate\": \"token-{i}-{j}\",\n" +
                "        \"count\": 1,\n" +
                "        \"channel\": \"us\",\n" +
                "        \"randomStatus\": false\n" +
                "      }\n" +
                "    ]\n" +
                "  },\n" +
                "  {\n" +
                "    \"deviceIdTemplate\": \"gb-device-{i}\",\n" +
                "    \"count\": 50,\n" +
                "    \"groupId\": \"gb\",\n" +
                "    \"agentVersion\": \"1.0.1\",\n" +
                "    \"supportedApps\": [\"demoApp\"],\n" +
                "    \"tokens\": [\n" +
                "      {\n" +
                "        \"tokenIdTemplate\": \"gb-token-{i}-{j}\",\n" +
                "        \"count\": 1,\n" +
                "        \"channel\": \"gb\",\n" +
                "        \"randomStatus\": false\n" +
                "      }\n" +
                "    ]\n" +
                "  }\n" +
                "]";
    }
} 