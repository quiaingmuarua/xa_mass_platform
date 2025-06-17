package com.xa.mass.mock.runner;

import com.xa.mass.core.server.MassWebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;

@Component
@Profile("server")
public class WebSocketServerStarter implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(WebSocketServerStarter.class);

    @Autowired
    private MassWebSocketServer webSocketServer;

    @Value("${custom.websocket.server.port:18088}")
    private int serverPort;

    @Override
    public void run(String... args) throws Exception {
        if (!webSocketServer.isRunning()) {
            log.info("Starting WebSocket server on port {}...", serverPort);
            webSocketServer.start(serverPort);
        } else {
            log.info("WebSocket server already running on port {}", serverPort);
        }
    }
}
