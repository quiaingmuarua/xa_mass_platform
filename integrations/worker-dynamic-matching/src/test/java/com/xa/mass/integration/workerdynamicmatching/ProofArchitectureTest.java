package com.xa.mass.integration.workerdynamicmatching;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ProofArchitectureTest {
    @Test void harnessUsesOnlyPublicHttpAndDeliveryJson() throws Exception {
        String build = Files.readString(Path.of("build.gradle"));
        var dependencies = Pattern.compile("project\\('([^']+)'\\)").matcher(build);
        while (dependencies.find()) assertEquals(":transport:worker-delivery-contract", dependencies.group(1));
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                var imports = Pattern.compile("(?m)^import (?:static )?([^;]+);").matcher(source);
                while (imports.find()) {
                    String name = imports.group(1);
                    assertTrue(name.startsWith("java.") || name.startsWith("com.xa.mass.integration.workerdynamicmatching.")
                            || name.equals("com.xa.mass.workerdelivery.json.Jsons"), name);
                }
                for (String forbidden : Set.of("Class.forName", "java.lang.reflect", "RedisClient", "/workers:prepare",
                        ":start", ":stop", ":shutdown", "/results:export", "platform.worker.properties.updated"))
                    assertFalse(source.contains(forbidden), forbidden);
            }
        }
    }
}
