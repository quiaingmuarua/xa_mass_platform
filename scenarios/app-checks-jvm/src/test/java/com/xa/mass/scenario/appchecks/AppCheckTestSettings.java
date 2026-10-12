package com.xa.mass.scenario.appchecks;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.charset.StandardCharsets;

final class AppCheckTestSettings {
    static String seed() {
        try (var input = AppCheckTestSettings.class.getResourceAsStream("/app-checks/preview-configuration.json")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    static AppCheckSettings settings() { return AppCheckSettings.parse(Jsons.parseObject(seed())); }
}
