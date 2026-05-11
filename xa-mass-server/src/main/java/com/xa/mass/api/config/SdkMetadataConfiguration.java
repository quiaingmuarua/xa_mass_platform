package com.xa.mass.api.config;

import com.xa.mass.sdk.catalog.DefaultProjectEventCatalogFactory;
import com.xa.mass.sdk.catalog.SdkMetadataCatalog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the default SDK catalog view used by project, event, and capability
 * read APIs when the host runtime does not provide a live SDK-backed bean.
 */
@Configuration
public class SdkMetadataConfiguration {

    @Bean
    @ConditionalOnMissingBean(SdkMetadataCatalog.class)
    public SdkMetadataCatalog sdkMetadataCatalog() {
        return DefaultProjectEventCatalogFactory.createDefaultProjectRegistry();
    }
}
