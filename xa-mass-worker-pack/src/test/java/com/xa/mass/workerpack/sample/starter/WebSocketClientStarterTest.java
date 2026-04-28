package com.xa.mass.workerpack.sample.starter;

import com.xa.mass.base.model.Worker;
import com.xa.mass.workerpack.sample.client.ClientSessionManager;
import com.xa.mass.workerpack.sample.config.SampleConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketClientStarterTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void beanIsNotCreatedWhenAutoStartDisabled() {
        contextRunner
                .withPropertyValues("sample.client.auto-start=false")
                .run(context -> assertTrue(context.getBeansOfType(WebSocketClientStarter.class).isEmpty()));
    }

    @Test
    void beanIsCreatedWhenAutoStartEnabled() {
        contextRunner
                .withPropertyValues("sample.client.auto-start=true")
                .run(context -> assertEquals(1, context.getBeansOfType(WebSocketClientStarter.class).size()));
    }

    @Test
    void onApplicationReadyStartsClientsOnlyOnce() {
        TestWebSocketClientStarter starter = new TestWebSocketClientStarter(List.of(worker("worker-1")));
        SampleConfig sampleConfig = new SampleConfig();
        sampleConfig.getClient().setWebsocketUri("ws://localhost:18088/ws");
        starter.setSampleConfig(sampleConfig);
        setField(starter, AbstractSampleWorkerClientStarter.class, "clientSessionManager", new ClientSessionManager());
        starter.setMaxPoolSize(5);
        starter.setConnectionTimeout(5);
        starter.setRetryAttempts(1);
        starter.setRetryDelay(1);
        starter.setTaskResultStatus("SUCCESS");

        starter.onApplicationReady(null);
        starter.onApplicationReady(null);

        assertEquals(1, starter.establishInvocations);
        assertEquals("ws://localhost:18088/ws", starter.baseUriUsed);
        starter.shutdown();
    }

    @Test
    void onApplicationReadySkipsPingAndConnectWhenNoDevicesExist() {
        TestWebSocketClientStarter starter = new TestWebSocketClientStarter(List.of());
        SampleConfig sampleConfig = new SampleConfig();
        sampleConfig.getClient().setWebsocketUri("ws://localhost:18088/ws");
        starter.setSampleConfig(sampleConfig);
        setField(starter, AbstractSampleWorkerClientStarter.class, "clientSessionManager", new ClientSessionManager());
        starter.setMaxPoolSize(5);
        starter.setConnectionTimeout(5);
        starter.setRetryAttempts(1);
        starter.setRetryDelay(1);
        starter.setTaskResultStatus("SUCCESS");

        starter.onApplicationReady(null);

        assertEquals(0, starter.establishInvocations);
        assertNull(starter.baseUriUsed);
        starter.shutdown();
    }

    @Test
    void websocketClientFilterUsesConcreteAdapterIdInsteadOfTransportFamily() {
        TestWebSocketClientStarter starter = new TestWebSocketClientStarter(List.of());

        assertTrue(starter.isWebSocketClientWorker(worker("worker-ws", "websocket", "realtime")));
        assertFalse(starter.isWebSocketClientWorker(worker("worker-socket", "socket", "realtime")));
        assertFalse(starter.isWebSocketClientWorker(worker("worker-polling", "polling", "polling")));
        assertFalse(starter.isWebSocketClientWorker(worker("worker-missing", null, "realtime")));
    }

    private static Worker worker(String workerId) {
        return worker(workerId, null, null);
    }

    private static Worker worker(String workerId, String adapterId, String onlineStrategy) {
        Worker w = new Worker();
        w.setWorkerId(workerId);
        w.setAdapterId(adapterId);
        w.setOnlineStrategy(onlineStrategy);
        return w;
    }

    private static void setField(Object target, Class<?> declaringClass, String fieldName, Object value) {
        try {
            var field = declaringClass.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static class TestWebSocketClientStarter extends WebSocketClientStarter {
        private final List<Worker> workers;
        private int establishInvocations;
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
    }

    @Configuration(proxyBeanMethods = false)
    @Import(WebSocketClientStarter.class)
    static class TestConfig {
        @Bean
        SampleConfig sampleConfig() {
            return new SampleConfig();
        }

        @Bean
        ClientSessionManager clientSessionManager() {
            return new ClientSessionManager();
        }
    }
}

