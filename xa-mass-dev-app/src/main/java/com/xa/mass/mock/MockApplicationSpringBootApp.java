package com.xa.mass.mock;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.mock.bootstrap.MockRuntimeDataLoader;
import com.xa.mass.sdk.MassSdk;
import com.xa.mass.sdk.MassSdkApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Verified mainline Spring Boot entry for the full mock runtime.
 */
@SpringBootApplication(scanBasePackages = {"com.xa.mass.mock", "com.xa.mass.api"})
public class MockApplicationSpringBootApp {

    private static final Logger log = LoggerFactory.getLogger(MockApplicationSpringBootApp.class);

    @Value("${mass.websocket.port:18088}")
    private int massWebSocketPort;

    @Value("${mass.gateway.max-connections:1000}")
    private int maxConnections;

    @Value("${mass.engine.worker-threads:8}")
    private int workerThreads;

    @Value("${mass.mock.data.workers:mock/mock_workers.json}")
    private String workersConfigPath;

    @Value("${mass.mock.data.tasks:mock/mock_tasks.json}")
    private String tasksConfigPath;

    @Value("${mass.mock.data.worker-contexts:mock/mock_worker_contexts.json}")
    private String workerContextsConfigPath;

    @Value("${mass.mock.data.rules:mock/mock_rules.json}")
    private String rulesConfigPath;

    public static void main(String[] args) {
        String profile = System.getProperty("spring.profiles.active");
        if (profile == null || profile.isBlank()) {
            profile = "dev";
            System.setProperty("spring.profiles.active", profile);
        }

        log.info("Starting mock full-stack application");
        log.info("Active profile: {}", profile);

        ConfigurableApplicationContext context = SpringApplication.run(MockApplicationSpringBootApp.class, args);
        Environment environment = context.getEnvironment();
        String httpPort = environment.getProperty("local.server.port",
                environment.getProperty("server.port", "8088"));
        String webSocketPort = environment.getProperty("mass.websocket.port", "18088");

        log.info("Mock full-stack application started");
        log.info("==============================");
        log.info("HTTP control console: http://localhost:{}/", httpPort);
        log.info("HTTP API docs: http://localhost:{}/doc.html", httpPort);
        log.info("Gateway WebSocket: ws://localhost:{}/ws", webSocketPort);
        log.info("==============================");
    }

    @Bean(destroyMethod = "stop")
    @Profile("dev")
    public MassSdkApplication fullStackRuntimeApplication(
            @Qualifier("outputQueue") MessageQueue<Envelope> outputQueue,
            @Qualifier("inputQueue") MessageQueue<Envelope> inputQueue) {
        return MassSdk.builder()
                .server(massWebSocketPort)
                .gateway(gateway -> gateway
                        .enabled(true)
                        .maxConnections(maxConnections)
                        .inputQueue(inputQueue)
                        .outputQueue(outputQueue)
                        .queueMode())
                .engine(engine -> engine
                        .enabled(true)
                        .workerThreads(workerThreads)
                        .bootstrapDataProvider(mockRuntimeDataLoader()))
                .build();
    }

    @Bean
    @Profile("dev")
    public MockRuntimeDataLoader mockRuntimeDataLoader() {
        return new MockRuntimeDataLoader(
                workersConfigPath,
                workerContextsConfigPath,
                tasksConfigPath,
                rulesConfigPath
        );
    }

    @Bean
    @Profile("dev")
    public CommandLineRunner fullStackStarter(MassSdkApplication app, MockRuntimeDataLoader mockRuntimeDataLoader) {
        return args -> {
            log.info("Starting internal gateway + engine runtime");
            try {
                app.start();
                if (!app.isRunning()) {
                    throw new IllegalStateException("MassApplication failed to start properly");
                }

                Thread.sleep(1000L);

                try {
                    mockRuntimeDataLoader.loadInto(app);
                    LogUtils.clearMdc();
                    log.info("Mock data loaded");
                } catch (Exception e) {
                    LogUtils.clearMdc();
                    log.warn("Mock data load failed but startup will continue: {}", e.getMessage());
                }

                try {
                    app.publishTaskEvents();
                    LogUtils.clearMdc();
                    log.info("Initial task events published");
                } catch (Exception e) {
                    LogUtils.clearMdc();
                    log.warn("Initial task event publish failed: {}", e.getMessage());
                }

                LogUtils.clearMdc();
                log.info("Spring Boot HTTP API is ready");
                log.info("Full-stack runtime startup complete");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LogUtils.clearMdc();
                log.error("Startup interrupted", e);
                throw new RuntimeException("Startup process was interrupted", e);
            } catch (RuntimeException e) {
                LogUtils.clearMdc();
                log.error("Full-stack startup failed: {}", e.getMessage(), e);
                throw e;
            } catch (Exception e) {
                LogUtils.clearMdc();
                log.error("Full-stack startup failed", e);
                throw new RuntimeException("Failed to start full-stack services", e);
            }
        };
    }
}
