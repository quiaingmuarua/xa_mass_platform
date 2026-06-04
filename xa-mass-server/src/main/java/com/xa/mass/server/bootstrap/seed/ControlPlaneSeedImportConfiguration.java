package com.xa.mass.server.bootstrap.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.sdk.MassSdkApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ResourceLoader;

@Configuration
@Profile({"dev", "prod"})
public class ControlPlaneSeedImportConfiguration {

    @Bean
    @Order(2)
    @ConditionalOnProperty(prefix = "mass.control-plane.seed", name = "enabled", havingValue = "true")
    public CommandLineRunner controlPlaneSeedImportRunner(MassSdkApplication app,
                                                          ObjectMapper objectMapper,
                                                          ResourceLoader resourceLoader,
                                                          @Value("${mass.control-plane.seed.catalog-location:}") String catalogLocation,
                                                          @Value("${mass.control-plane.seed.rules-location:}") String rulesLocation,
                                                          @Value("${mass.control-plane.seed.mode:apply}") String mode) {
        return args -> new ControlPlaneSeedImporter(app, objectMapper, resourceLoader)
                .importSeed(new ControlPlaneSeedImporter.ControlPlaneSeedImportRequest(
                        blankToNull(catalogLocation),
                        blankToNull(rulesLocation),
                        blankToDefault(mode, "apply")
                ));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
