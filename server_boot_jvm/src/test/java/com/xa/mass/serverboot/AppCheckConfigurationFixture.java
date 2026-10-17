package com.xa.mass.serverboot;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Isolated test files, reused by a fixture's Server restarts; never a Preview configuration. */
public final class AppCheckConfigurationFixture {
    public static Map<String, Object> properties(long maxCount) {
        return properties(maxCount, Map.of());
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> properties(long maxCount, Map<String, Object> parametersByCheck) {
        try {
            Path root = Path.of("build", "app-check-config-fixtures", UUID.randomUUID().toString()).toAbsolutePath();
            Files.createDirectories(root);
            Map<String, Object> seed;
            try (var input = AppCheckConfigurationFixture.class.getResourceAsStream("/app-checks/preview-configuration.json")) {
                seed = Jsons.parseObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            ((Map<String, Map<String,Object>>) seed.get("checks")).forEach((checkId, app) -> {
                if (parametersByCheck.containsKey(checkId)) app.put("parameters", parametersByCheck.get(checkId));
            });
            Path file = root.resolve("seed.json"); Files.writeString(file, Jsons.toJson(seed));
            return Map.of("xa.mass.scenarios.app-checks.configuration.path", root.resolve("configuration.json").toString(),
                    "xa.mass.scenarios.app-checks.configuration.seed", file.toUri().toString(),
                    "xa.mass.worker-assembly.fixed-window.pools.assignment-window.max-count", maxCount);
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    static String[] arguments(long maxCount, String... arguments) {
        var result = new ArrayList<>(List.of(arguments));
        properties(maxCount).forEach((key, value) -> result.add("--" + key + "=" + value));
        return result.toArray(String[]::new);
    }
}
