package com.xa.mass.transport.websocket.dispatcher;

import com.xa.mass.transport.runtime.AdapterResultIngressSink;
import com.xa.mass.transport.websocket.frame.WebSocketJsonFrameParser;
import com.xa.mass.transport.websocket.frame.WebSocketResultIngressFrameReader;

import java.util.Objects;

/**
 * WebSocket adapter-owned dispatch runtime context.
 */
public final class WebSocketDispatcherContext {
    private final String adapterId;
    private final WebSocketJsonFrameParser frameParser;
    private final WebSocketResultIngressFrameReader resultFrameReader;
    private final AdapterResultIngressSink resultIngressSink;

    public WebSocketDispatcherContext(String adapterId,
                                       WebSocketJsonFrameParser frameParser,
                                       WebSocketResultIngressFrameReader resultFrameReader,
                                       AdapterResultIngressSink resultIngressSink) {
        this.adapterId = requireAdapterId(adapterId);
        this.frameParser = Objects.requireNonNull(frameParser, "frameParser");
        this.resultFrameReader = Objects.requireNonNull(resultFrameReader, "resultFrameReader");
        this.resultIngressSink = resultIngressSink;
    }

    public String getAdapterId() {
        return adapterId;
    }

    public WebSocketJsonFrameParser getFrameParser() {
        return frameParser;
    }

    public WebSocketResultIngressFrameReader getResultFrameReader() {
        return resultFrameReader;
    }

    public AdapterResultIngressSink getResultIngressSink() {
        return resultIngressSink;
    }

    private static String requireAdapterId(String adapterId) {
        Objects.requireNonNull(adapterId, "adapterId");
        if (adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        return adapterId.trim();
    }
}
