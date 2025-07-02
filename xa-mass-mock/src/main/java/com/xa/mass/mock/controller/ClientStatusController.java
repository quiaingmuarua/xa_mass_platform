package com.xa.mass.mock.controller;

import com.xa.mass.mock.client.ClientSessionManager;
import com.xa.mass.mock.client.MassWebSocketClientImpl;
import com.xa.mass.mock.starter.WebSocketClientStarter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 客户端状态控制器
 * 提供客户端连接状态的监控和管理
 */
@RestController
@RequestMapping("/status")
@Profile("client")
public class ClientStatusController {

    private static final Logger log = LoggerFactory.getLogger(ClientStatusController.class);

    @Autowired
    private ClientSessionManager clientSessionManager;

    @Autowired
    private WebSocketClientStarter clientStarter;

    /**
     * 获取客户端概览状态
     */
    @GetMapping
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("service", "WebSocket Client Mock");
        status.put("status", "running");
        status.put("timestamp", System.currentTimeMillis());
        status.put("connectionStats", clientStarter.getConnectionStats());
        return status;
    }

    /**
     * 获取客户端连接列表
     */
    @GetMapping("/clients")
    public Map<String, Object> getClients() {
        Collection<MassWebSocketClientImpl> clients = clientSessionManager.getAllClients();
        
        Map<String, Object> result = new HashMap<>();
        result.put("total", clients.size());
        result.put("timestamp", System.currentTimeMillis());
        
        if (!clients.isEmpty()) {
            result.put("clients", clients.stream()
                    .map(this::getClientInfo)
                    .collect(Collectors.toList()));
        } else {
            result.put("clients", new Object[0]);
        }
        
        return result;
    }

    /**
     * 获取特定客户端信息
     */
    @GetMapping("/clients/{deviceId}")
    public Map<String, Object> getClient(@PathVariable String deviceId) {
        Collection<MassWebSocketClientImpl> allClients = clientSessionManager.getAllClients();
        MassWebSocketClientImpl client = allClients.stream()
                .filter(c -> c.getDeviceId().equals(deviceId))
                .findFirst()
                .orElse(null);
        
        if (client != null) {
            return getClientInfo(client);
        } else {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Client not found");
            error.put("deviceId", deviceId);
            return error;
        }
    }

    /**
     * 断开特定客户端连接
     */
    @DeleteMapping("/clients/{deviceId}")
    public Map<String, Object> disconnectClient(@PathVariable String deviceId) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            Collection<MassWebSocketClientImpl> allClients = clientSessionManager.getAllClients();
            MassWebSocketClientImpl client = allClients.stream()
                    .filter(c -> c.getDeviceId().equals(deviceId))
                    .findFirst()
                    .orElse(null);
            
            if (client != null) {
                client.disconnect();
                clientSessionManager.removeClient(deviceId);
                result.put("success", true);
                result.put("message", "Client disconnected successfully");
                result.put("deviceId", deviceId);
                log.info("✅ 客户端 {} 已断开连接", deviceId);
            } else {
                result.put("success", false);
                result.put("error", "Client not found");
                result.put("deviceId", deviceId);
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "Failed to disconnect client: " + e.getMessage());
            result.put("deviceId", deviceId);
            log.error("❌ 断开客户端 {} 失败", deviceId, e);
        }
        
        return result;
    }

    /**
     * 获取客户端统计信息
     */
    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        Collection<MassWebSocketClientImpl> clients = clientSessionManager.getAllClients();
        
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalClients", clients.size());
        stats.put("activeConnections", clients.stream()
                .filter(MassWebSocketClientImpl::isConnected)
                .count());
        stats.put("inactiveConnections", clients.stream()
                .filter(client -> !client.isConnected())
                .count());
        stats.put("timestamp", System.currentTimeMillis());
        
        return stats;
    }

    /**
     * 获取客户端详细信息
     */
    private Map<String, Object> getClientInfo(MassWebSocketClientImpl client) {
        Map<String, Object> info = new HashMap<>();
        info.put("deviceId", client.getDeviceId());
        info.put("connected", client.isConnected());
        info.put("uri", client.getURI().toString());
        info.put("lastActivity", System.currentTimeMillis()); // 可以扩展为实际的活动时间
        
        // 连接状态详情
        Map<String, Object> connection = new HashMap<>();
        connection.put("isOpen", client.isOpen());
        connection.put("isClosing", client.isClosing());
        connection.put("isClosed", client.isClosed());
        info.put("connection", connection);
        
        return info;
    }
} 