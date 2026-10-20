package com.xa.mass.serverboot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Finite static-delivery assertions shared by the existing Boot composition proofs. */
final class DocumentationDelivery {
    private DocumentationDelivery() { }

    static void verify(HttpClient client, URI base) throws Exception {
        Path frontend = Path.of(System.getProperty("xa.mass.test.frontend"));
        var assets = new LinkedHashSet<String>();
        String commit = null;
        var identity = Pattern.compile("<meta\\s+name=\"xa-mass-docs-commit\"\\s+content=\"([0-9a-f]{40})\"[^>]*>");
        var dependency = Pattern.compile("(?:src|href)=\"(/docs/[^\"?#]+\\.(?:js|css))(?:[?#][^\"]*)?\"");
        for (String path : List.of("/docs", "/docs/", "/docs/index.html", "/docs/doc/guide/first-task.html")) {
            var response = get(client, base, path);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/html");
            String file = path.equals("/docs") || path.equals("/docs/") ? "docs/index.html" : path.substring(1);
            assertThat(response.body()).as(path).isEqualTo(Files.readString(frontend.resolve(file)));
            var marker = identity.matcher(response.body());
            assertThat(marker.find()).as("documentation identity at %s", path).isTrue();
            if (commit == null) commit = marker.group(1);
            assertThat(marker.group(1)).as(path).isEqualTo(commit);
            var references = dependency.matcher(response.body());
            while (references.find()) assets.add(references.group(1));
        }
        assertThat(assets).anyMatch(path -> path.endsWith(".js")).anyMatch(path -> path.endsWith(".css"));
        for (String path : assets) {
            var response = get(client, base, path);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).as(path).isEqualTo(Files.readString(frontend.resolve(path.substring(1))));
            assertThat(response.headers().firstValue("Content-Type").orElse("")).doesNotContain("text/html");
        }
        for (String path : List.of("/docs/missing-page.html", "/docs/assets/missing.js")) {
            assertThat(get(client, base, path).statusCode()).as(path).isEqualTo(404);
        }
    }

    private static HttpResponse<String> get(HttpClient client, URI base, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
