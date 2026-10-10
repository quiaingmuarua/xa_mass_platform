package com.xa.mass.workersimulator.messaging;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static com.xa.mass.workersimulator.messaging.MessageProtocol.*;

/** Host-local wire adapter. It owns networking, never acceptance or Reporter state. */
final class MessageLabHttp implements AutoCloseable {
    private final Object gate = new Object();
    private URI baseUri;
    private HttpClient http;
    private ThreadPoolExecutor callbacks;
    private boolean closed;

    void start(URI listener) {
        synchronized (gate) {
            if (closed) throw new IllegalStateException("Messages closed");
            if (http != null) throw new IllegalStateException("Messages HTTP already started");
            if (!"http".equals(listener.getScheme()) || !"127.0.0.1".equals(listener.getHost())
                    || listener.getPort() < 1 || listener.getRawQuery() != null || listener.getRawUserInfo() != null)
                throw new IllegalArgumentException("Expected the Host loopback listener");
            baseUri = listener;
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            callbacks = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128), task -> {
                Thread thread = new Thread(task, "message-lab-callback"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        }
    }

    MessageSendOperation.Acceptance send(Map<String, Object> message, Map<String, Object> sender,
            String callbackId, Duration remainingBudget) throws IOException, InterruptedException, MessageSendOperation.Rejected {
        HttpClient client; URI target;
        synchronized (gate) {
            if (closed || http == null) throw new IllegalStateException("Message business HTTP unavailable");
            client = http; target = baseUri.resolve("/lab/v1/messages/send");
        }
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("message", message); envelope.put("sender", sender); envelope.put("callbackId", callbackId);
        var response = post(client, target, envelope, remainingBudget);
        if (response.statusCode() >= 400 && response.statusCode() < 500)
            throw new MessageSendOperation.Rejected(response.statusCode() == 400, response.statusCode() == 400
                    ? "Lab rejected message input or identity conflict" : "Lab rejected message acceptance");
        if (response.statusCode() != 200) throw new IllegalStateException("Lab message response is uncertain");
        Map<String, Object> decoded = Jsons.parseObject(response.body());
        return new MessageSendOperation.Acceptance(object(decoded, "snapshot"), nullableCallback(decoded));
    }

    /** Queue admission only; HTTP and the completion callback run after this returns. */
    boolean offer(MessageLab.Receipt receipt, Consumer<CallbackResult> completion) {
        HttpClient client; URI target; ThreadPoolExecutor executor;
        synchronized (gate) {
            if (closed || http == null) return false;
            client = http; executor = callbacks;
            target = baseUri.resolve("/lab/v1/workers/" + segment(receipt.sender().get("workerGroupId")) + "/"
                    + segment(receipt.sender().get("replicaKey")) + ":inputs");
        }
        try {
            executor.execute(() -> {
                int status = 0; Boolean reported = null; String failure = null;
                try {
                    var response = post(client, target, Map.of("eventName", "message.receipt", "payload",
                            Map.of("callbackId", receipt.callbackId(), "receiptId", receipt.id(), "snapshot", receipt.snapshot())),
                            Duration.ofSeconds(5));
                    status = response.statusCode();
                    if (status == 200) {
                        Object result = Jsons.parseObject(response.body()).get("reportAccepted");
                        if (!(result instanceof Boolean)) throw new IllegalArgumentException("Invalid callback response");
                        reported = (Boolean) result;
                    } else failure = "callback_http_rejected";
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); failure = "callback_interrupted";
                } catch (Exception error) { failure = "callback_transport_failed"; }
                completion.accept(new CallbackResult(status, reported, failure));
            });
            return true;
        } catch (RejectedExecutionException full) { return false; }
    }

    private static HttpResponse<String> post(HttpClient client, URI target, Map<String, Object> payload, Duration timeout)
            throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(target).timeout(timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Jsons.toJson(payload))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String segment(Object value) { return URLEncoder.encode((String) value, StandardCharsets.UTF_8).replace("+", "%20"); }

    @Override public void close() {
        HttpClient closingHttp; ThreadPoolExecutor closingCallbacks;
        synchronized (gate) {
            if (closed) return;
            closed = true; closingHttp = http; closingCallbacks = callbacks;
        }
        if (closingCallbacks != null) closingCallbacks.shutdownNow();
        if (closingHttp != null) closingHttp.shutdownNow();
    }

    record CallbackResult(int status, Boolean reported, String failure) {}
}
