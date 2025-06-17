package com.xa.mass.mock;

import com.xa.mass.core.client.MassWebSocketClient;
import com.xa.mass.core.client.MassWebSocketClientImpl;
import com.xa.mass.core.server.MassWebSocketServer;
import io.netty.channel.Channel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;

@SpringBootTest
@ActiveProfiles("test")
public class WebSocketClientServerIntegrationTest {

    @Autowired
    private MassWebSocketServer webSocketServer;

    @Test
    public void testServerAndClientConnection() throws Exception {
        // 启动 WebSocket 服务端
        webSocketServer.start(18088);
        Assertions.assertTrue(webSocketServer.isRunning());

        // 创建客户端并连接
        String deviceId = "test-device-001";
        MassWebSocketClient client = new MassWebSocketClientImpl(new URI("ws://localhost:18088/ws"), deviceId);
        client.connect(new URI("ws://localhost:18088/ws"));
        Assertions.assertTrue(client.isConnected());

        // 检查服务端是否能获取到客户端 Channel
        Channel channel = webSocketServer.getClientChannel(deviceId);
        // 由于 handshake 及注册流程，可能需要等待一会
        Thread.sleep(1000);
        channel = webSocketServer.getClientChannel(deviceId);
        Assertions.assertNotNull(channel, "服务端应能获取到客户端 Channel");

        // 断开连接
        client.disconnect();
        Thread.sleep(500);
        webSocketServer.stop();
        Assertions.assertFalse(webSocketServer.isRunning());
    }
} 