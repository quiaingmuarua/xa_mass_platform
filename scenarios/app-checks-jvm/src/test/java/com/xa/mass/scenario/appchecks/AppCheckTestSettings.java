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
        return new AppCheckContracts(java.util.List.of(AppCheckContracts.registration(), AppCheckContracts.simulatedRegistration(), AppCheckContracts.originalCarrier(), AppCheckContracts.uint32Prime(), AppCheckContracts.md5Prefix(), AppCheckContracts.originalCarrierBatch(), AppCheckContracts.md5PrefixBatch()));
    }
    static AppCheckSettings.Check simulated(String label, String group) {
        return new AppCheckSettings.Check(label, group, settings().requireCheck("app-a-registration").execution(),
                AppCheckContracts.SIMULATED, null, 1L);
    }
    static AppCheckSettings settings() { return AppCheckSettings.parse(Jsons.parseObject(seed()), contracts()); }
}
