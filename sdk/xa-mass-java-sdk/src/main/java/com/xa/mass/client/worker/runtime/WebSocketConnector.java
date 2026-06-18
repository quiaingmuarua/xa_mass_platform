package com.xa.mass.client.worker.runtime;

import java.net.URI;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
interface WebSocketConnector {
    CompletableFuture<WebSocket> connect(URI endpoint, WebSocket.Listener listener);
}
