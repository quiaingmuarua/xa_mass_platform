package com.xa.mass.workerpack.sample.client;

import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * Transport client contract for a dev-app mock worker.
 */
public interface SampleWorkerClient {
    String adapterId();

    String getWorkerId();

    void connect(URI serverUri) throws Exception;

    void disconnect() throws Exception;

    boolean isConnected();

    void sendMessage(String message) throws Exception;

    boolean connectBlocking(long timeout, TimeUnit timeUnit) throws InterruptedException;
}

