package com.xa.mass.api.config;

import com.xa.mass.sdk.catalog.DefaultProjectEventCatalogFactory;
import com.xa.mass.sdk.catalog.ProjectEventCatalog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the default SDK metadata catalog used by the read-only metadata APIs.
 */
@Configuration
public class SdkMetadataConfiguration {

    @Bean
    @ConditionalOnMissingBean(ProjectEventCatalog.class)
    public ProjectEventCatalog projectEventCatalog() {
        return DefaultProjectEventCatalogFactory.createDefaultRegistry();
    }
}
