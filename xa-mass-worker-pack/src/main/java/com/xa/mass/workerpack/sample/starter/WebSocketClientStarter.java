package com.xa.mass.workerpack.sample.starter;

import com.xa.mass.base.model.Worker;
import com.xa.mass.workerpack.sample.client.MockWorkerClient;
import com.xa.mass.workerpack.sample.client.MockWorkerWebSocketClient;
import com.xa.mass.workerpack.sample.config.SampleConfig;
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
@ConditionalOnProperty(prefix = "sample.client", name = "auto-start", havingValue = "true")
public class WebSocketClientStarter extends AbstractMockWorkerClientStarter {

    private static final Logger log = LoggerFactory.getLogger(WebSocketClientStarter.class);
    protected SampleConfig mockConfig;

    @Value("${sample.client.connection-timeout:10}")
    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    @Value("${sample.client.max-pool-size:20}")
    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    @Value("${sample.client.retry-attempts:3}")
    public void setRetryAttempts(int retryAttempts) {
        this.retryAttempts = retryAttempts;
    }

    @Value("${sample.client.retry-delay:5}")
    public void setRetryDelay(int retryDelay) {
        this.retryDelay = retryDelay;
    }

    @Value("${sample.client.task-result-status:SUCCESS}")
    public void setTaskResultStatus(String taskResultStatus) {
        this.taskResultStatus = taskResultStatus;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setSampleConfig(SampleConfig mockConfig) {
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
        return mockConfig.getClient().getWebsocketUri();
    }

    @Override
    protected MockWorkerClient createClient(URI baseUri, String workerId, String taskResultStatus) {
        return new MockWorkerWebSocketClient(baseUri, workerId, taskResultStatus);
    }
}
