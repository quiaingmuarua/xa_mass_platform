package com.xa.mass.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class JavaExternalSdkArchitectureGuardTest {
    private static final Path MAIN_SOURCE = Path.of("src/main/java");
    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "import com.xa.mass.engine.",
            "import com.xa.mass.starter.",
            "import com.xa.mass.worker.runtime.",
            "import com.xa.mass.api.",
            "import com.xa.mass.transport.runtime.",
            "import com.xa.mass.sdk.auth.",
            "import com.xa.mass.sdk.authz.",
            "import com.xa.mass.sdk.catalog.",
            "import com.xa.mass.sdk.Task",
            "import com.xa.mass.sdk.Worker"
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
}
