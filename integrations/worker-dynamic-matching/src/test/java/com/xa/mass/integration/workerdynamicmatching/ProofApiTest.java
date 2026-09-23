package com.xa.mass.integration.workerdynamicmatching;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ProofApiTest {
    @Test void closingDoesNotWaitForAnOutstandingObservation() throws Exception {
        var received = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        var api = new ProofApi("http://127.0.0.1:" + server.getAddress().getPort());
        var reader = Executors.newSingleThreadExecutor();
        var request = reader.submit(() -> api.call("GET", "/", null, true));
        try {
            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1), api::close);
        } finally {
            release.countDown();
            request.cancel(true);
            api.close();
            reader.shutdownNow();
            server.stop(0);
        }
    }
}
