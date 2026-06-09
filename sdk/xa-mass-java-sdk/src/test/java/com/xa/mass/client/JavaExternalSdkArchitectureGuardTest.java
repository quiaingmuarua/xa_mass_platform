package com.xa.mass.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaExternalSdkArchitectureGuardTest {
    private static final Path MAIN_SOURCE = Path.of("src/main/java");
    private static final Path WORKER_HANDLER_SOURCE =
            Path.of("src/main/java/com/xa/mass/client/worker/handler");
    private static final List<String> PUBLIC_PLATFORM_ROUTE_PREFIXES = List.of(
            "\"/api/v1",
            "\"/worker-api/v1"
    );
    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "import com.xa.mass.engine.",
            "import com.xa.mass.starter.",
            "import com.xa.mass.worker.runtime.",
            "import com.xa.mass.command.",
            "import com.xa.mass.kernel.spi.",
            "import com.xa.mass.base.",
            "import com.xa.mass.api.",
            "import com.xa.mass.transport.runtime.",
            "import com.xa.mass.transport.websocket.",
            "import com.xa.mass.transport.socket.",
            "import com.xa.mass.transport.polling.",
            "import com.xa.mass.sdk.auth.",
            "import com.xa.mass.sdk.authz.",
            "import com.xa.mass.sdk.catalog.",
            "import com.xa.mass.sdk.Task",
            "import com.xa.mass.sdk.Worker"
    );
    private static final List<String> FORBIDDEN_ARTIFACT_IDS = List.of(
            "xa-mass-embedded-sdk",
            "xa-mass-embedded-sdk-api",
            "xa-mass-base",
            "xa-mass-kernel-spi",
            "xa-mass-worker-pack",
            "xa-mass-engine",
            "xa-mass-server",
            "xa-mass-worker-runtime",
            "xa-mass-transport-websocket",
            "xa-mass-transport-socket",
            "xa-mass-transport-polling",
            "xa-mass-transport-runtime"
    );

    @Test
    void productionCodeDoesNotImportRuntimeServerOrEmbeddedSdkOwners() throws IOException {
        try (var paths = Files.walk(MAIN_SOURCE)) {
            List<Path> javaFiles = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
            for (Path javaFile : javaFiles) {
                String source = Files.readString(javaFile);
                for (String forbiddenImport : FORBIDDEN_IMPORTS) {
                    assertFalse(source.contains(forbiddenImport),
                            javaFile + " must not contain " + forbiddenImport);
                }
            }
        }
    }

    @Test
    void pomDoesNotDependOnRuntimeServerEmbeddedSdkOrBaseArtifacts() throws IOException {
        for (Path pomPath : List.of(Path.of("pom.xml"), Path.of("pom.consumer.xml"))) {
            String pom = Files.readString(pomPath);
            for (String forbiddenArtifactId : FORBIDDEN_ARTIFACT_IDS) {
                String token = "<artifactId>" + forbiddenArtifactId + "</artifactId>";
                assertFalse(pom.contains(token), pomPath + " must not contain " + token);
            }
        }
    }

    @Test
    void workerHandlerRuntimeDoesNotDependOnSessionOrTransportPackages() throws IOException {
        try (var paths = Files.walk(WORKER_HANDLER_SOURCE)) {
            List<Path> javaFiles = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
            for (Path javaFile : javaFiles) {
                String source = Files.readString(javaFile);
                assertFalse(source.contains("import com.xa.mass.client.worker.session."),
                        javaFile + " must not import worker session types");
                assertFalse(source.contains("import com.xa.mass.transport."),
                        javaFile + " must not import transport types");
            }
        }
    }

    @Test
    void integrationsProductionCodeDoesNotHardcodePublicPlatformRouteLiteralsOutsideSdk() throws IOException {
        Path repoRoot = resolveRepoRoot();
        Path integrationsRoot = repoRoot.resolve("integrations");
        List<String> violations = new ArrayList<>();

        try (var paths = Files.walk(integrationsRoot)) {
            List<Path> javaFiles = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !hasPathSegment(path, "target"))
                    .filter(path -> !isAllowedRouteLiteralOwner(repoRoot, path))
                    .toList();
            for (Path javaFile : javaFiles) {
                String source = Files.readString(javaFile);
                for (String routePrefix : PUBLIC_PLATFORM_ROUTE_PREFIXES) {
                    if (source.contains(routePrefix)) {
                        violations.add(repoRoot.relativize(javaFile) + " contains " + routePrefix);
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Java integrations must use MassPlatform or worker sessions for /api/v1 and /worker-api/v1 calls. "
                        + "Only SDK internals/tests and explicit roadmap exceptions may hard-code route literals: "
                        + violations);
    }

    private static boolean isAllowedRouteLiteralOwner(Path repoRoot, Path path) {
        Path relative = repoRoot.relativize(path);
        String normalized = relative.toString().replace('\\', '/');
        return normalized.startsWith("sdk/xa-mass-java-sdk/")
                || normalized.contains("/src/test/");
    }

    private static boolean hasPathSegment(Path path, String segment) {
        for (Path part : path) {
            if (part.toString().equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static Path resolveRepoRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        for (Path cursor = current; cursor != null; cursor = cursor.getParent()) {
            if (Files.exists(cursor.resolve("pom.xml"))
                    && Files.exists(cursor.resolve("sdk/xa-mass-java-sdk/pom.xml"))) {
                return cursor;
            }
        }
        throw new IllegalStateException("Repo root not found from cwd=" + current);
    }
}
