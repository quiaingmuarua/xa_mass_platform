package com.xa.mass.starter.config;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.JsonParser;

/**
 * 引擎配置类
 */
public  class EngineConfig {
    private boolean enabled = true;
    private int workerThreads = 8;
    private String mockConfigPath = "mock_config.json";
    private boolean mockMode = false;
    private JsonObject mockConfigRoot;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }

    public String getMockConfigPath() { return mockConfigPath; }
    public void setMockConfigPath(String mockConfigPath) { this.mockConfigPath = mockConfigPath; }

    public boolean isMockMode() { return mockMode; }
    public void setMockMode(boolean mockMode) { this.mockMode = mockMode; }

    public JsonObject getMockConfigRoot() {
        if (this.mockConfigRoot == null) {
            String configPath = this.getMockConfigPath();
            String jsonDsl;
            try {
                jsonDsl = Files.readString(Path.of(configPath));
                // 可加日志: System.out.println("Loaded mock config from file: " + configPath);
            } catch (IOException e) {
                // 可加日志: System.out.println("No external mock config found, using default.");
                jsonDsl = getDefaultMockConfig();
            }
            this.mockConfigRoot = JsonParser.parseString(jsonDsl).getAsJsonObject();
        }
        return this.mockConfigRoot;
    }

    // 默认 mock 配置
    private static String getDefaultMockConfig() {
        return "{\n" +
                "  \"devices\": " + com.xa.mass.engine.monkey.MonkeyDeviceGenerator.exampleJsonDsl() + ",\n" +
                "  \"tasks\": " + com.xa.mass.engine.monkey.MonkeyTaskGenerator.exampleTasksJsonDsl() + "\n" +
                "}";
    }

    public void setMockConfigRoot(JsonObject mockConfigRoot) { this.mockConfigRoot = mockConfigRoot; }
}