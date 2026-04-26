package com.xa.mass.mock.starter;

import com.xa.mass.base.model.Worker;
import com.xa.mass.mock.client.MockWorkerClient;
import com.xa.mass.mock.client.MockWorkerWebSocketClient;
import com.xa.mass.mock.config.MockConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.net.URI;

/**
 * Starts mock WebSocket clients after the full dev stack is ready.
 */
@Component
@ConditionalOnProperty(prefix = "mock.client", name = "auto-start", havingValue = "true")
public class WebSocketClientStarter extends AbstractMockWorkerClientStarter {

    private static final Logger log = LoggerFactory.getLogger(WebSocketClientStarter.class);
    protected MockConfig mockConfig;

    @Value("${mock.client.connection-timeout:10}")
    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    @Value("${mock.client.max-pool-size:20}")
    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    @Value("${mock.client.retry-attempts:3}")
    public void setRetryAttempts(int retryAttempts) {
        this.retryAttempts = retryAttempts;
    }

    @Value("${mock.client.retry-delay:5}")
    public void setRetryDelay(int retryDelay) {
        this.retryDelay = retryDelay;
    }

    @Value("${mock.client.task-result-status:SUCCESS}")
    public void setTaskResultStatus(String taskResultStatus) {
        this.taskResultStatus = taskResultStatus;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setMockConfig(MockConfig mockConfig) {
        this.mockConfig = mockConfig;
    }

    @Override
    protected List<Worker> loadWorkers() {
        return super.loadWorkers();
    }

    protected boolean isWebSocketClientWorker(Worker worker) {
        return isClientWorker(worker);
    }

    @Override
    protected Logger logger() {
        return log;
    }

    @Override
    protected String adapterId() {
        return "websocket";
    }

    @Override
    protected String adapterDisplayName() {
        return "WebSocket";
    }

    @Override
    protected String resolveBaseUri() {
        return mockConfig.getClient().getUri();
    }

    @Override
    protected MockWorkerClient createClient(URI baseUri, String workerId, String taskResultStatus) {
        return new MockWorkerWebSocketClient(baseUri, workerId, taskResultStatus);
    }
}
