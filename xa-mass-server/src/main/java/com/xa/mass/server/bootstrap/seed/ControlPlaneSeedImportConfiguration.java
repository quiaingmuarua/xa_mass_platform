package com.xa.mass.server.bootstrap.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.api.auth.operator.OperatorCredentialStore;
import com.xa.mass.storage.api.CatalogMetadataStore;
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
    @Order(0)
    @ConditionalOnProperty(prefix = "mass.control-plane.seed", name = "enabled", havingValue = "true")
    public CommandLineRunner controlPlaneEarlySeedImportRunner(MassSdkApplication app,
                                                               CatalogMetadataStore catalogMetadataStore,
                                                               OperatorCredentialStore operatorCredentialStore,
                                                               ObjectMapper objectMapper,
                                                               ResourceLoader resourceLoader,
                                                               @Value("${mass.control-plane.seed.catalog-location:}") String catalogLocation,
                                                               @Value("${mass.control-plane.seed.rules-location:}") String rulesLocation,
                                                               @Value("${mass.control-plane.seed.operator-credentials-location:}")
                                                               String operatorCredentialsLocation,
                                                               @Value("${mass.control-plane.seed.mode:apply}") String mode) {
        return args -> {
            String normalizedCatalogLocation = blankToNull(catalogLocation);
            String normalizedRulesLocation = blankToNull(rulesLocation);
            String normalizedOperatorCredentialsLocation = blankToNull(operatorCredentialsLocation);
            requireAnySeedLocation(normalizedCatalogLocation, normalizedRulesLocation,
                    normalizedOperatorCredentialsLocation);
            if (normalizedCatalogLocation == null && normalizedOperatorCredentialsLocation == null) {
                return;
            }
            new ControlPlaneSeedImporter(
                    app,
                    catalogMetadataStore,
                    operatorCredentialStore,
                    objectMapper,
                    resourceLoader)
                    .importSeed(new ControlPlaneSeedImporter.ControlPlaneSeedImportRequest(
                            normalizedCatalogLocation,
                            null,
                            normalizedOperatorCredentialsLocation,
                            blankToDefault(mode, "apply")
                    ));
        };
    }

    @Bean
    @Order(3)
    @ConditionalOnProperty(prefix = "mass.control-plane.seed", name = "enabled", havingValue = "true")
    public CommandLineRunner controlPlaneRuleSeedImportRunner(MassSdkApplication app,
                                                              CatalogMetadataStore catalogMetadataStore,
                                                              OperatorCredentialStore operatorCredentialStore,
                                                              ObjectMapper objectMapper,
                                                              ResourceLoader resourceLoader,
                                                              @Value("${mass.control-plane.seed.rules-location:}") String rulesLocation,
                                                              @Value("${mass.control-plane.seed.mode:apply}") String mode) {
        return args -> {
            String normalizedRulesLocation = blankToNull(rulesLocation);
            if (normalizedRulesLocation == null) {
                return;
            }
            new ControlPlaneSeedImporter(
                    app,
                    catalogMetadataStore,
                    operatorCredentialStore,
                    objectMapper,
                    resourceLoader)
                    .importSeed(new ControlPlaneSeedImporter.ControlPlaneSeedImportRequest(
                            null,
                            normalizedRulesLocation,
                            null,
                            blankToDefault(mode, "apply")
                    ));
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static void requireAnySeedLocation(String catalogLocation,
                                               String rulesLocation,
                                               String operatorCredentialsLocation) {
        if (catalogLocation == null && rulesLocation == null && operatorCredentialsLocation == null) {
            throw new IllegalArgumentException("control-plane seed is enabled but no seed location is configured");
        }
    }
}
