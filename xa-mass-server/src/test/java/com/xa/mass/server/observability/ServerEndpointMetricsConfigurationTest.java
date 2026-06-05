package com.xa.mass.server.observability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerEndpointMetricsConfigurationTest {

    private static final Path SERVER_ROOT = Path.of(".");

    @Test
    void devExposesHealthAndMetricsWhileProdExposesOnlyHealthByDefault() throws IOException {
        String devConfig = read("src/main/resources/application-dev.yml");
        String prodConfig = read("src/main/resources/application-prod.yml");

        assertTrue(devConfig.contains("management:")
                        && devConfig.contains("include: health,metrics"),
                "dev profile must expose health and metrics for local endpoint diagnosis");
        assertTrue(prodConfig.contains("management:")
                        && prodConfig.contains("include: health")
                        && !prodConfig.contains("include: health,metrics")
                        && !prodConfig.contains("prometheus"),
                "prod-like profile must not expose broad actuator or prometheus endpoints by default");
    }

    @Test
    void firstPassUsesActuatorWithoutPrometheusRegistryOrEndpointCounters() throws IOException {
        String pom = read("pom.xml");
        assertTrue(pom.contains("spring-boot-starter-actuator"),
                "server must include actuator for http.server.requests metrics");
        assertTrue(!pom.contains("micrometer-registry-prometheus"),
                "prometheus registry is deferred and must not be added in the first pass");

        List<String> violations;
        try (Stream<Path> paths = Files.walk(SERVER_ROOT.resolve("src/main/java"))) {
            violations = paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> forbiddenMetricsTagViolations(path).stream())
                    .toList();
        }

        assertTrue(violations.isEmpty(),
                "endpoint metrics must not add high-cardinality tags or hand-rolled http.server.requests meters:\n"
                        + String.join("\n", violations));
    }

    private List<String> forbiddenMetricsTagViolations(Path path) {
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (!source.contains("MeterRegistry")
                    && !source.contains("Timer.builder")
                    && !source.contains("Counter.builder")
                    && !source.contains("Gauge.builder")) {
                return List.of();
            }
            return Stream.of("http.server.requests", ".tag(\"principalId\"", ".tag(\"traceId\"", ".tag(\"taskId\"",
                            ".tag(\"workerId\"", ".tag(\"commandId\"", ".tag(\"apiKey\"", ".tag(\"keyId\"",
                            ".tag(\"sessionId\"", ".tag(\"requestBody\"", ".tag(\"rawUrl\"", ".tag(\"rawQuery\"")
                    .filter(source::contains)
                    .map(token -> path + " contains forbidden endpoint metrics token " + token)
                    .toList();
        } catch (IOException e) {
            return List.of(path + " could not be read: " + e.getMessage());
        }
    }

    private String read(String relativePath) throws IOException {
        return Files.readString(SERVER_ROOT.resolve(relativePath), StandardCharsets.UTF_8);
    }
}
