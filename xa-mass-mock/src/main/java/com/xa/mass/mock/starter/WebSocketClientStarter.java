package com.xa.mass.mock.starter;

import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.mock.client.ClientSessionManager;
import com.xa.mass.mock.client.MassWebSocketClientImpl;
import com.xa.mass.mock.config.MockConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import com.xa.mass.engine.monkey.MonkeyDeviceGenerator;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Token;
import java.nio.charset.StandardCharsets;

/**
 * WebSocket 客户端启动器
 * 负责管理多个模拟设备客户端的连接和心跳
 */
@Component
@Profile("client")
public class WebSocketClientStarter implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(WebSocketClientStarter.class);

    @Autowired
    private MockConfig mockConfig;

    @Autowired
    private ClientSessionManager clientSessionManager;

    // 配置属性注入
    @Value("${mock.client.devices-config:mock/mock_devices.json}")
    private String devicesConfigPath;
    
    @Value("${mock.client.connection-timeout:10}")
    private int connectionTimeout;
    
    @Value("${mock.client.max-pool-size:20}")
    private int maxPoolSize;
    
    @Value("${mock.client.ping-interval:10}")
    private int pingInterval;
    
    @Value("${mock.client.ping-delay:5}")
    private int pingDelay;
    
    @Value("${mock.client.retry-attempts:3}")
    private int retryAttempts;
    
    @Value("${mock.client.retry-delay:5}")
    private int retryDelay;

    private ExecutorService clientExecutor;
    private ScheduledExecutorService pingScheduler;
    private volatile boolean isShuttingDown = false;

    @Override
    public void run(String... args) throws Exception {
        log.info("🔌 开始启动 WebSocket 客户端...");
        
        String baseUri = mockConfig.getClient().getUri();
        log.info("目标服务器: {}", baseUri);
        log.info("设备配置文件: {}", devicesConfigPath);
        
        // 读取并解析设备配置
        List<Device> devices = loadDevices();
        if (devices == null || devices.isEmpty()) {
            log.warn("⚠️ 未找到设备配置，客户端启动终止");
            return;
        }
        
        log.info("📱 发现 {} 个设备，开始建立连接...", devices.size());
        
        // 初始化连接池
        clientExecutor = Executors.newFixedThreadPool(Math.min(devices.size(), maxPoolSize));
        
        // 批量建立连接
        establishConnections(devices, baseUri);
        
        // 启动心跳任务
        startPingTask();
        
        log.info("✅ WebSocket 客户端启动完成，活跃连接: {}", clientSessionManager.getClientCount());
    }

    /**
     * 加载设备配置
     */
    private List<Device> loadDevices() {
        List<Token> tokenList = new ArrayList<>();
        try (var is = getClass().getClassLoader().getResourceAsStream(devicesConfigPath)) {
            if (is == null) {
                log.error("❌ 未找到设备配置文件: {}", devicesConfigPath);
                return null;
            }
            String deviceJson = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return MonkeyDeviceGenerator.generateDevices(deviceJson, tokenList);
        } catch (Exception e) {
            log.error("❌ 加载设备配置失败", e);
            return null;
        }
    }

    /**
     * 建立设备连接
     */
    private void establishConnections(List<Device> devices, String baseUri) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(devices.size());
        List<Future<?>> futures = new ArrayList<>();
        
        for (Device device : devices) {
            String deviceId = device.getDeviceId();
            Future<?> future = clientExecutor.submit(() -> {
                try {
                    connectDeviceWithRetry(deviceId, baseUri);
                } catch (Exception e) {
                    log.error("❌ 设备 {} 连接失败", deviceId, e);
                } finally {
                    latch.countDown();
                }
            });
            futures.add(future);
        }
        
        // 等待所有连接尝试完成
        boolean completed = latch.await(devices.size() * (connectionTimeout + 5L), TimeUnit.SECONDS);
        if (!completed) {
            log.warn("⚠️ 部分设备连接超时");
        }
        
        // 检查连接结果
        int successCount = clientSessionManager.getClientCount();
        int failCount = devices.size() - successCount;
        log.info("📊 连接结果统计: 成功 {}, 失败 {}", successCount, failCount);
    }

    /**
     * 带重试的设备连接
     */
    private void connectDeviceWithRetry(String deviceId, String baseUri) {
        for (int attempt = 1; attempt <= retryAttempts; attempt++) {
            try {
                URI uri = new URI(baseUri);
                MassWebSocketClientImpl client = new MassWebSocketClientImpl(uri, deviceId);
                clientSessionManager.addClient(client);
                
                if (client.connectBlocking(connectionTimeout, TimeUnit.SECONDS)) {
                    log.info("✅ 设备 {} 连接成功 (尝试 {}/{})", deviceId, attempt, retryAttempts);
                    return;
                } else {
                    log.warn("⚠️ 设备 {} 连接超时 (尝试 {}/{})", deviceId, attempt, retryAttempts);
                }
            } catch (Exception e) {
                log.warn("⚠️ 设备 {} 连接异常 (尝试 {}/{}): {}", deviceId, attempt, retryAttempts, e.getMessage());
            }
            
            clientSessionManager.removeClient(deviceId);
            
            // 重试前等待
            if (attempt < retryAttempts) {
                try {
                    Thread.sleep(retryDelay * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        
        log.error("❌ 设备 {} 连接失败，已重试 {} 次", deviceId, retryAttempts);
    }

    /**
     * 启动心跳任务
     */
    private void startPingTask() {
        if (pingScheduler == null) {
            pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "client-ping-scheduler");
                t.setDaemon(true);
                return t;
            });
            
            pingScheduler.scheduleAtFixedRate(this::sendRandomPing, pingDelay, pingInterval, TimeUnit.SECONDS);
            log.info("💓 心跳任务已启动，间隔: {}秒", pingInterval);
        }
    }

    /**
     * 发送随机心跳
     */
    private void sendRandomPing() {
        if (isShuttingDown) {
            return;
        }
        
        Collection<MassWebSocketClientImpl> clients = clientSessionManager.getAllClients();
        if (clients.isEmpty()) {
            log.debug("📭 没有活跃的客户端连接");
            return;
        }
        
        List<MassWebSocketClientImpl> clientList = new ArrayList<>(clients);
        MassWebSocketClientImpl client = clientList.get(new Random().nextInt(clientList.size()));
        
        try {
            MassMessage ping = new MassMessage();
            ping.setMsgId("ping-" + client.getDeviceId() + "-" + System.currentTimeMillis());
            ping.setMsgType(MessageType.PING);
            ping.setFrom(MessageDirection.CLIENT);
            ping.setSubMsgType("heartbeat");
            
            MessageContext ctx = new MessageContext();
            ctx.setDeviceId(client.getDeviceId());
            ctx.setConnRole("messages_task");
            ping.setContext(ctx);
            
            client.send(new com.google.gson.Gson().toJson(ping));
            log.debug("💓 [{}] 发送心跳消息", client.getDeviceId());
        } catch (Exception e) {
            log.warn("⚠️ [{}] 发送心跳失败: {}", client.getDeviceId(), e.getMessage());
            // 心跳失败可能表示连接已断开，移除客户端
            clientSessionManager.removeClient(client.getDeviceId());
        }
    }

    /**
     * 获取连接统计信息
     */
    public String getConnectionStats() {
        int totalClients = clientSessionManager.getClientCount();
        return String.format("活跃连接: %d", totalClients);
    }

    @PreDestroy
    public void shutdown() {
        log.info("🛑 正在关闭 WebSocket 客户端...");
        isShuttingDown = true;
        
        // 关闭所有客户端连接
        Collection<MassWebSocketClientImpl> clients = clientSessionManager.getAllClients();
        log.info("📴 正在断开 {} 个客户端连接...", clients.size());
        
        for (MassWebSocketClientImpl client : clients) {
            try {
                client.disconnect();
                log.debug("✅ 客户端 {} 已断开", client.getDeviceId());
            } catch (Exception e) {
                log.warn("⚠️ 断开客户端 {} 时发生错误", client.getDeviceId(), e);
            }
        }
        
        // 关闭线程池
        if (clientExecutor != null) {
            clientExecutor.shutdown();
            try {
                if (!clientExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    clientExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                clientExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        if (pingScheduler != null) {
            pingScheduler.shutdown();
            try {
                if (!pingScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    pingScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                pingScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        log.info("✅ WebSocket 客户端已关闭");
    }
}
