package com.xa.mass.server.openapi;

import com.xa.mass.server.XaMassServerApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

public final class OpenApiSnapshotExporter {

    private OpenApiSnapshotExporter() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1 || arguments[0].isBlank()) {
            throw new IllegalArgumentException(
                    "Expected one OpenAPI snapshot output path"
            );
        }
        Path output = Path.of(arguments[0]).toAbsolutePath().normalize();
        String snapshot;
        try (ConfigurableApplicationContext context =
                     new SpringApplicationBuilder(XaMassServerApplication.class)
                             .profiles("test")
                             .properties(Map.of(
                                     "server.address", "127.0.0.1",
                                     "server.port", "0",
                                     "spring.main.banner-mode", "off",
                                     "logging.level.root", "WARN"
                             ))
                             .run()) {
            int port = ((WebServerApplicationContext) context)
                    .getWebServer()
                    .getPort();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                                    "http://127.0.0.1:" + port + "/v3/api-docs"
                            ))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "OpenAPI endpoint returned HTTP " + response.statusCode()
                );
            }
            snapshot = OpenApiSnapshotSupport.canonicalize(response.body());
        }
        Files.createDirectories(output.getParent());
        Files.writeString(output, snapshot, StandardCharsets.UTF_8);
        System.out.println("Exported OpenAPI snapshot to " + output);
    }
}
