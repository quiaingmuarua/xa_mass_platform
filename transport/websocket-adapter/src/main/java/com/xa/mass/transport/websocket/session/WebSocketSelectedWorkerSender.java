package com.xa.mass.transport.websocket.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WebSocketSelectedWorkerSender {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketSelectedWorkerSender.class);

    private final WebSocketSessionStore store;

    public WebSocketSelectedWorkerSender(WebSocketSessionStore store) {
        this.store = store;
    }

    public boolean sendToSelectedWorker(String selectedWorkerId, String message) {
        WebSocketSessionRecord record = store.activeRecordForWorker(selectedWorkerId);
        if (record == null) {
            logger.warn("Failed to send to selectedWorkerId={}. Channel not found or inactive.", selectedWorkerId);
            return false;
        }
        record.send(message);
        return true;
    }
}
