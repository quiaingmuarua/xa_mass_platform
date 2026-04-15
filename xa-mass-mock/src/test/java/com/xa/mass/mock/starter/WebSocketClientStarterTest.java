package com.xa.mass.mock.starter;

import com.xa.mass.base.model.Worker;
import com.xa.mass.mock.client.ClientSessionManager;
import com.xa.mass.mock.config.MockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketClientStarterTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void beanIsNotCreatedWhenAutoStartDisabled() {
        contextRunner
                .withPropertyValues("mock.client.auto-start=false")
                .run(context -> assertTrue(context.getBeansOfType(WebSocketClientStarter.class).isEmpty()));
    }

    @Test
    void beanIsCreatedWhenAutoStartEnabled() {
        contextRunner
                .withPropertyValues("mock.client.auto-start=true")
                .run(context -> assertEquals(1, context.getBeansOfType(WebSocketClientStarter.class).size()));
    }

    @Test
    void onApplicationReadyStartsClientsOnlyOnce() {
        TestWebSocketClientStarter starter = new TestWebSocketClientStarter(List.of(worker("device-1")));
        MockConfig mockConfig = new MockConfig();
        mockConfig.getClient().setUri("ws://localhost:18088/ws");
        setField(starter, "mockConfig", mockConfig);
        setField(starter, "clientSessionManager", new ClientSessionManager());
        setField(starter, "maxPoolSize", 5);

        starter.onApplicationReady(null);
        starter.onApplicationReady(null);

        assertEquals(1, starter.establishInvocations);
        assertEquals(1, starter.pingInvocations);
        assertEquals("ws://localhost:18088/ws", starter.baseUriUsed);
        starter.shutdown();
    }

    @Test
    void onApplicationReadySkipsPingAndConnectWhenNoDevicesExist() {
        TestWebSocketClientStarter starter = new TestWebSocketClientStarter(List.of());
        MockConfig mockConfig = new MockConfig();
        mockConfig.getClient().setUri("ws://localhost:18088/ws");
        setField(starter, "mockConfig", mockConfig);
        setField(starter, "clientSessionManager", new ClientSessionManager());
        setField(starter, "maxPoolSize", 5);

        starter.onApplicationReady(null);

        assertEquals(0, starter.establishInvocations);
        assertEquals(0, starter.pingInvocations);
        assertNull(starter.baseUriUsed);
        starter.shutdown();
    }

    private static Worker worker(String workerId) {
        Worker w = new Worker();
        w.setWorkerId(workerId);
        return w;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = WebSocketClientStarter.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static class TestWebSocketClientStarter extends WebSocketClientStarter {
        private final List<Worker> workers;
        private int establishInvocations;
        private int pingInvocations;
        private String baseUriUsed;

        private TestWebSocketClientStarter(List<Worker> workers) {
            this.workers = workers;
        }

        @Override
        protected List<Worker> loadWorkers() {
            return workers;
        }

        @Override
        protected void establishConnections(List<Worker> workers, String baseUri) {
            establishInvocations++;
            baseUriUsed = baseUri;
        }

        @Override
        protected void startPingTask() {
            pingInvocations++;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(WebSocketClientStarter.class)
    static class TestConfig {
        @Bean
        MockConfig mockConfig() {
            return new MockConfig();
        }

        @Bean
        ClientSessionManager clientSessionManager() {
            return new ClientSessionManager();
        }
    }
}
