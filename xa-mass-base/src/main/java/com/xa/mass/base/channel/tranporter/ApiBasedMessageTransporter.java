package com.xa.mass.base.channel.tranporter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Template/stub transporter backed by external HTTP APIs.
 *
 * <p>This class is not a working implementation. All HTTP call sites are
 * placeholders only.
 */
public class ApiBasedMessageTransporter<I, O> implements MessageTransporter<I, O> {

    private static final Logger logger = LoggerFactory.getLogger(ApiBasedMessageTransporter.class);

    private final String inputApiUrl;
    private final String outputApiUrl;
    private final String apiKey;

    public ApiBasedMessageTransporter(String inputApiUrl, String outputApiUrl, String apiKey) {
        this.inputApiUrl = inputApiUrl;
        this.outputApiUrl = outputApiUrl;
        this.apiKey = apiKey;
    }

    @Override
    public void sendInput(I message) {
        logger.info("Send input message through external API: {}", message);
    }

    @Override
    public I receiveInput(long timeout, TimeUnit unit) throws InterruptedException {
        long endTime = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < endTime) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        return null;
    }

    @Override
    public void sendOutput(O message) {
        logger.info("Send output message through external API: {}", message);
    }

    @Override
    public O receiveOutput(long timeout, TimeUnit unit) throws InterruptedException {
        long endTime = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < endTime) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        return null;
    }

    @Override
    public int inputQueueSize() {
        logger.debug("Query input queue size from external API {}", inputApiUrl);
        return -1;
    }

    @Override
    public int outputQueueSize() {
        logger.debug("Query output queue size from external API {}", outputApiUrl);
        return -1;
    }

    public String getApiKey() {
        return apiKey;
    }
}
