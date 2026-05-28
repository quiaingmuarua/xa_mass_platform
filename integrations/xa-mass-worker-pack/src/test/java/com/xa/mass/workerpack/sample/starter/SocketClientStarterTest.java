package com.xa.mass.workerpack.sample.starter;

import com.xa.mass.sdk.model.WorkerSnapshot;
import com.xa.mass.workerpack.sample.client.ClientSessionManager;
import com.xa.mass.workerpack.sample.config.SampleConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketClientStarterTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void beanIsNotCreatedWhenSocketAdapterDisabled() {
        contextRunner
                .withPropertyValues("sample.client.auto-start=true", "mass.socket.enabled=false")
                .run(context -> assertTrue(context.getBeansOfType(SocketClientStarter.class).isEmpty()));
    }

    @Test
    void beanIsCreatedWhenAutoStartAndSocketAdapterEnabled() {
        contextRunner
                .withPropertyValues("sample.client.auto-start=true", "mass.socket.enabled=true")
                .run(context -> assertEquals(1, context.getBeansOfType(SocketClientStarter.class).size()));
    }

    @Test
    void socketClientFilterUsesConcreteAdapterId() {
        TestSocketClientStarter starter = new TestSocketClientStarter(List.of());

        assertTrue(starter.isClientWorker(worker("worker-socket", "socket", "realtime")));
        assertFalse(starter.isClientWorker(worker("worker-ws", "websocket", "realtime")));
        assertFalse(starter.isClientWorker(worker("worker-polling", "polling", "polling")));
        assertFalse(starter.isClientWorker(worker("worker-missing", null, "realtime")));
    }

    @Test
    void resolveBaseUriUsesPublishedBoundPortWhenAvailable() {
        TestSocketClientStarter starter = new TestSocketClientStarter(List.of());
        SampleConfig sampleConfig = new SampleConfig();
        sampleConfig.getClient().setSocketHost("127.0.0.1");
        sampleConfig.getClient().setSocketPort(19089);
        starter.setSampleConfig(sampleConfig);

        String previous = System.getProperty("mass.socket.bound-port");
        System.setProperty("mass.socket.bound-port", "28089");
        try {
            assertEquals("tcp://127.0.0.1:28089", starter.resolveBaseUri());
        } finally {
            if (previous == null) {
                System.clearProperty("mass.socket.bound-port");
            } else {
                System.setProperty("mass.socket.bound-port", previous);
            }
        }
    }

    private static WorkerSnapshot worker(String workerId, String adapterId, String onlineStrategy) {
        return new WorkerSnapshot(
                workerId,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                adapterId,
                onlineStrategy,
                1,
                java.util.Map.of(),
                null,
                null
        );
    }

    private static class TestSocketClientStarter extends SocketClientStarter {
        private final List<WorkerSnapshot> workers;

        private TestSocketClientStarter(List<WorkerSnapshot> workers) {
            this.workers = workers;
        }

        @Override
        protected List<WorkerSnapshot> loadWorkers() {
            return workers;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(SocketClientStarter.class)
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
