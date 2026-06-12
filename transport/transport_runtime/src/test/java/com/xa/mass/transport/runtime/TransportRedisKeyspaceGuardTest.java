package com.xa.mass.transport.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportRedisKeyspaceGuardTest {

    @Test
    void currentCodeAndDocsDoNotKeepOldDistributedDefaultNamespace() throws IOException {
        Path root = repoRoot();
        String oldNamespace = "xa:mass:transport:" + "distributed" + ":v1";
        List<String> offenders = new ArrayList<>();
        for (Path searchRoot : List.of(
                root.resolve("transport"),
                root.resolve("sdk"),
                root.resolve("xa-mass-server"),
                root.resolve("doc"),
                root.resolve("roadmap"),
                root.resolve("README.md"),
                root.resolve("AGENTS.md"))) {
            if (!Files.exists(searchRoot)) {
                continue;
            }
            try (Stream<Path> paths = scanTextFiles(searchRoot)) {
                paths.filter(TransportRedisKeyspaceGuardTest::isCurrentSourceOrDoc)
                        .filter(path -> contains(path, oldNamespace))
                        .map(root::relativize)
                        .map(Path::toString)
                        .forEach(offenders::add);
            }
        }

        assertTrue(offenders.isEmpty(), () -> "old transport distributed namespace remains in " + offenders);
    }

    @Test
    void transportRuntimeProductionCodeDoesNotUseDeprecatedPresenceFamilies() throws IOException {
        Path root = repoRoot();
        Path sourceRoot = root.resolve("transport/transport_runtime/src/main/java");
        List<String> forbidden = List.of("owner-shards", "worker-routes", "route-presence", ":workers",
                "worker-route", ":routes");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> paths = scanTextFiles(sourceRoot)) {
            paths.filter(path -> forbidden.stream().anyMatch(token -> contains(path, token)))
                    .map(root::relativize)
                    .map(Path::toString)
                    .forEach(offenders::add);
        }

        assertTrue(offenders.isEmpty(), () -> "deprecated transport presence key families remain in " + offenders);
    }

    @Test
    void transportBoundaryBaselineDoesNotDocumentRouteKeyOnlyDeliveryQueue() throws IOException {
        Path baseline = repoRoot().resolve("transport/TRANSPORT_BOUNDARY_BASELINE.md");
        String content = Files.readString(baseline, StandardCharsets.UTF_8);

        assertTrue(!content.contains("q:<routeKey>"),
                "transport boundary baseline must not document routeKey-only delivery queues");
        assertTrue(content.contains("worker-index:<selectedWorkerId>"),
                "transport boundary baseline must document selected-worker delivery queue selector");
    }

    private static Stream<Path> scanTextFiles(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            return Stream.of(path);
        }
        return Files.walk(path)
                .filter(Files::isRegularFile);
    }

    private static boolean isCurrentSourceOrDoc(Path path) {
        String normalized = path.toString().replace('\\', '/');
        if (normalized.contains("/target/")
                || normalized.contains("/build/")
                || normalized.contains("/node_modules/")
                || normalized.contains("/doc/archive/")) {
            return false;
        }
        return normalized.endsWith(".java")
                || normalized.endsWith(".md")
                || normalized.endsWith(".yml")
                || normalized.endsWith(".yaml")
                || normalized.endsWith(".properties")
                || normalized.endsWith(".json");
    }

    private static boolean contains(Path path, String token) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).contains(token);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }

    private static Path repoRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null) {
            if (Files.exists(path.resolve("transport/AGENTS.md"))
                    && Files.exists(path.resolve("doc/PROOF_REGISTRY.md"))) {
                return path;
            }
            path = path.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root");
    }
}
