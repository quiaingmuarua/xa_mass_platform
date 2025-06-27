package com.xa.mass.mock.starter;

import com.google.gson.JsonObject;
import com.xa.mass.starter.config.EngineConfig;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MockTaskEngineStarter 配置文件读取测试
 */
public class MockTaskEngineStarterTest {
    
    private static final Logger log = LoggerFactory.getLogger(MockTaskEngineStarterTest.class);
    
    @Test
    public void testDefaultConfigPath() {
        log.info("=== 测试默认配置文件路径 ===");
        
        EngineConfig config = new EngineConfig();
        config.setMockMode(true);
        
        // 测试默认配置路径
        String configPath = config.getMockConfigPath();
        assertEquals("mock_config.json", configPath);
        
        // 测试配置文件读取
        JsonObject root = config.getMockConfigRoot();
        assertNotNull(root);
        assertTrue(root.has("devices"));
        assertTrue(root.has("tasks"));
        
        log.info("默认配置文件读取成功");
    }
    
    @Test
    public void testClasspathConfigPath() {
        log.info("=== 测试 classpath 配置文件路径 ===");
        
        EngineConfig config = new EngineConfig();
        config.setMockMode(true);
        config.setMockConfigPath("classpath:mock_config.json");
        
        // 测试配置文件读取
        JsonObject root = config.getMockConfigRoot();
        assertNotNull(root);
        assertTrue(root.has("devices"));
        assertTrue(root.has("tasks"));
        
        log.info("classpath 配置文件读取成功");
    }
    
    @Test
    public void testRelativeConfigPath() {
        log.info("=== 测试相对路径配置文件 ===");
        
        EngineConfig config = new EngineConfig();
        config.setMockMode(true);
        config.setMockConfigPath("mock_config.json"); // 相对路径，会从 classpath 读取
        
        // 测试配置文件读取
        JsonObject root = config.getMockConfigRoot();
        assertNotNull(root);
        assertTrue(root.has("devices"));
        assertTrue(root.has("tasks"));
        
        log.info("相对路径配置文件读取成功");
    }
    
    @Test
    public void testNonExistentConfigPath() {
        log.info("=== 测试不存在的配置文件路径 ===");
        
        EngineConfig config = new EngineConfig();
        config.setMockMode(true);
        config.setMockConfigPath("non_existent_config.json");
        
        // 测试配置文件读取（应该回退到默认配置）
        JsonObject root = config.getMockConfigRoot();
        assertNotNull(root);
        assertTrue(root.has("devices"));
        assertTrue(root.has("tasks"));
        
        log.info("不存在的配置文件路径回退到默认配置成功");
    }
} 