package com.xa.mass.base.channel.tranporter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Template/stub transporter backed by external HTTP APIs.
 *
 * <p><b>This class is NOT a working implementation.</b> All HTTP call sites are
 * marked with TODO comments as placeholder scaffolding. The class exists to show
 * how to implement {@link MessageTransporter} for an HTTP-based transport; it
 * must not be used in production until those TODOs are completed.
 *
 * <p>To enable {@code apiMode()} in the SDK builder, replace this stub with a
 * real HTTP client (e.g. Java {@code HttpClient} or OkHttp) that calls the
 * configured {@code inputApiUrl}/{@code outputApiUrl} endpoints.
 *
 * @param <T> message type
 */
public class ApiBasedMessageTransporter<T> implements MessageTransporter<T> {

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
    public void sendInput(T message) {
        logger.info("Send input message through external API: {}", message);
        // TODO: Implement an HTTP request to the external input API.
        // Example: httpClient.post(inputApiUrl, message.toJson(), headers)
    }

    @Override
    public T receiveInput(long timeout, TimeUnit unit) throws InterruptedException {
        logger.debug("Poll input message from external API with timeout {} {}", timeout, unit);

        long endTime = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < endTime) {
            try {
                // TODO: Implement an HTTP request to the external input API.
                // T message = httpClient.get(inputApiUrl, headers);
                // if (message != null) {
                //     return message;
                // }
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        return null;
    }

    @Override
    public void sendOutput(T message) {
        logger.info("Send output message through external API: {}", message);
        // TODO: Implement an HTTP request to the external output API.
        // Example: httpClient.post(outputApiUrl, message.toJson(), headers)
    }

    @Override
    public T receiveOutput(long timeout, TimeUnit unit) throws InterruptedException {
        logger.debug("Poll output message from external API with timeout {} {}", timeout, unit);

        long endTime = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < endTime) {
            try {
                // TODO: Implement an HTTP request to the external output API.
                // T message = httpClient.get(outputApiUrl, headers);
                // if (message != null) {
                //     return message;
                // }
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
        logger.debug("Query input queue size from external API");
        // TODO: Implement an HTTP request to query the input queue size.
        // return httpClient.get(inputApiUrl + "/size", headers);
        return -1;
    }

    @Override
    public int outputQueueSize() {
        logger.debug("Query output queue size from external API");
        // TODO: Implement an HTTP request to query the output queue size.
        // return httpClient.get(outputApiUrl + "/size", headers);
        return -1;
    }
}
