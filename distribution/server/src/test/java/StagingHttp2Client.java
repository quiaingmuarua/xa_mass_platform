import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.TrustManagerFactory;

/** Finite JDK/Nginx transport fixture. No Runtime, Redis, Worker or production credentials. */
public final class StagingHttp2Client {
    private static final String ACCESS_KEY = "xak_proof_gateway_admin";
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final String CALL = "/api/v1/tasks/proof/items:call";

    public static void main(String[] args) {
        try {
            prove(args);
        } catch (Throwable error) {
            // Exception messages and request bodies remain outside the public proof summary.
            System.out.println("{\"status\":\"failed\",\"failureType\":\"" + error.getClass().getSimpleName() + "\"}");
            System.exit(1);
        }
    }

    private static void prove(String[] args) throws Exception {
        URI gateway = URI.create(args[0]);
        URI backend = URI.create(args[1]);
        String mode = args[3];
        boolean fallback = mode.equals("http1-fallback");
        boolean negative = mode.equals("default-limit");
        HttpClient.Version expected = fallback ? HttpClient.Version.HTTP_1_1 : HttpClient.Version.HTTP_2;
        // Select the fixture's administrator TLS context explicitly; keep hostname verification enabled.
        var context = trust(Path.of(args[2]));
        var parameters = context.getDefaultSSLParameters();
        parameters.setServerNames(List.of(new SNIHostName("localhost")));
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        var client = HttpClient.newBuilder().sslContext(context)
                .sslParameters(parameters)
                .connectTimeout(Duration.ofSeconds(5)).build(); // The same JDK default preference as CallApi.
        var control = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
        int sent = 0, expectedRefusals = 0, concurrent = fallback ? 0 : negative ? 128 : 256;
        try {
            var ready = client.send(request(gateway, "/api/v1/me").GET().build(), HttpResponse.BodyHandlers.ofString());
            require(ready.statusCode() == 200 && ready.version() == expected, "preferred protocol negotiation");
            var denied = client.send(request(gateway,
                    "/api/v1/worker-groups/staging-workers/workers:prepare")
                    .POST(HttpRequest.BodyPublishers.ofString("forbidden")).build(), HttpResponse.BodyHandlers.ofString());
            require(denied.statusCode() == 404 && denied.version() == expected, "route denial uses the negotiated protocol");

            if (fallback) {
                checked(send(client, gateway, "fallback"), "fallback", expected);
                sent = 1;
            } else {
                var pending = new ArrayList<CompletableFuture<HttpResponse<String>>>();
                for (int index = 0; index < concurrent; index++) pending.add(send(client, gateway, "hold-" + index));
                awaitHeld(control, backend, concurrent);
                if (negative) {
                    try {
                        send(client, gateway, "over-limit").get(2, TimeUnit.SECONDS);
                        throw new AssertionError("default stream cap did not refuse the extra exchange");
                    } catch (ExecutionException error) {
                        Throwable cause = error.getCause();
                        require(cause instanceof IOException && "too many concurrent streams".equals(cause.getMessage()),
                                "JDK reports the stream cap rather than opening an extra connection");
                        expectedRefusals = 1;
                    }
                }
                release(control, backend);
                for (int index = 0; index < pending.size(); index++)
                    checked(pending.get(index), "hold-" + index, expected);
                sent = pending.size();
                if (!negative) {
                    // Keep one client and original identities beyond 1000 requests for both declared policies.
                    for (int index = 0; index < 1_001; index++) {
                        checked(send(client, gateway, "serial-" + index), "serial-" + index, expected);
                        sent++;
                    }
                }
            }
            System.out.printf("{\"status\":\"passed\",\"protocol\":\"%s\",\"successfulPosts\":%d,"
                    + "\"heldConcurrentPosts\":%d,\"expectedStreamRefusals\":%d,\"unexpectedFailures\":0}%n",
                    expected.name(), sent, concurrent, expectedRefusals);
        } finally {
            release(control, backend);
            client.shutdownNow(); control.shutdownNow();
            require(client.awaitTermination(Duration.ofSeconds(5)), "JDK data client terminates");
            require(control.awaitTermination(Duration.ofSeconds(5)), "JDK control client terminates");
        }
    }

    private static HttpRequest.Builder request(URI base, String path) {
        return HttpRequest.newBuilder(base.resolve(path)).timeout(WAIT).header("Authorization", "Bearer " + ACCESS_KEY);
    }

    private static CompletableFuture<HttpResponse<String>> send(HttpClient client, URI base, String id) {
        return client.sendAsync(request(base, CALL).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(id)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void checked(CompletableFuture<HttpResponse<String>> future, String id,
                                HttpClient.Version expected) throws Exception {
        var response = future.get(10, TimeUnit.SECONDS);
        require(response.statusCode() == 200 && response.version() == expected && response.body().equals(id),
                "complete successful response retains protocol and original correlation");
    }

    private static void awaitHeld(HttpClient client, URI backend, int count) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            var response = client.send(HttpRequest.newBuilder(backend.resolve("/proof/held")).timeout(WAIT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && Integer.parseInt(response.body()) == count) return;
            Thread.sleep(10);
        }
        throw new AssertionError("declared concurrent streams did not all reach the backend");
    }

    private static void release(HttpClient client, URI backend) throws Exception {
        var response = client.send(HttpRequest.newBuilder(backend.resolve("/proof/release")).timeout(WAIT)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        require(response.statusCode() == 200, "fixture releases only its own held requests");
    }

    private static SSLContext trust(Path ca) throws Exception {
        var store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        try (var input = Files.newInputStream(ca)) {
            store.setCertificateEntry("fixture-ca", CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(store);
        var context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), null);
        return context;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
