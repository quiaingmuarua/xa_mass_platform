package com.xa.mass.scenario.appchecks;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.charset.StandardCharsets;

final class AppCheckTestSettings {
    static String seed() {
        try (var input = AppCheckTestSettings.class.getResourceAsStream("/app-checks/preview-configuration.json")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    static AppCheckContracts contracts() {
        return new AppCheckContracts(java.util.List.of(AppCheckContracts.registration(), AppCheckContracts.simulatedRegistration()));
    }
    static AppCheckSettings.Application simulated(String label, String group) {
        return new AppCheckSettings.Application(label, group, settings().requireApplication("app-a").execution(),
                AppCheckContracts.SIMULATED, null);
    }
    static AppCheckSettings settings() { return AppCheckSettings.parse(Jsons.parseObject(seed()), contracts()); }
}
