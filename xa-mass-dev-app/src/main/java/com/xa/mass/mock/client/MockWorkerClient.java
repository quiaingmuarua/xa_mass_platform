package com.xa.mass.mock.client;

import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * Transport client contract for a dev-app mock worker.
 */
public interface MockWorkerClient {
    String getWorkerId();

    void connect(URI serverUri) throws Exception;

    void disconnect() throws Exception;

    boolean isConnected();

    void sendMessage(String message) throws Exception;

    boolean connectBlocking(long timeout, TimeUnit timeUnit) throws InterruptedException;
}
