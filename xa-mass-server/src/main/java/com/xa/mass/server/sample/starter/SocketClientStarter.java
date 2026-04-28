package com.xa.mass.server.sample.starter;

import com.xa.mass.server.sample.client.MockWorkerClient;
import com.xa.mass.server.sample.client.MockWorkerSocketClient;
import com.xa.mass.server.sample.config.SampleConfig;
import com.xa.mass.transport.socket.server.SocketTransportServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
@ConditionalOnExpression(
        "'${sample.client.auto-start:false}' == 'true' and '${mass.socket.enabled:false}' == 'true'"
)
public class SocketClientStarter extends AbstractMockWorkerClientStarter {

    private static final Logger log = LoggerFactory.getLogger(SocketClientStarter.class);

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
    protected Logger logger() {
        return log;
    }

    @Override
    protected String adapterId() {
        return "socket";
    }

    @Override
    protected String adapterDisplayName() {
        return "Socket";
    }

    @Override
    protected String resolveBaseUri() {
        String host = mockConfig.getClient().getSocketHost();
        int fallbackPort = mockConfig.getClient().getSocketPort();
        String boundPortValue = System.getProperty(SocketTransportServer.BOUND_PORT_PROPERTY);
        int port = parsePort(boundPortValue, fallbackPort);
        return "tcp://" + host + ":" + port;
    }

    @Override
    protected MockWorkerClient createClient(URI baseUri, String workerId, String taskResultStatus) {
        return new MockWorkerSocketClient(baseUri, workerId, taskResultStatus);
    }

    private int parsePort(String value, int fallbackPort) {
        if (value == null || value.isBlank()) {
            return fallbackPort;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallbackPort;
        } catch (NumberFormatException ignored) {
            return fallbackPort;
        }
    }
}
