package com.xa.mass.api.config;

import com.xa.mass.sdk.catalog.DefaultProjectEventCatalogFactory;
import com.xa.mass.sdk.catalog.ControlPlaneCatalog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Wires the default control-plane catalog used by project, event, and
 * capability read APIs when the host runtime does not provide a live bean.
 */
@Configuration
@Profile("!memory-local & !durable-local")
public class CatalogConfiguration {

    @Bean
    @ConditionalOnMissingBean(ControlPlaneCatalog.class)
    public ControlPlaneCatalog catalog() {
        return DefaultProjectEventCatalogFactory.createDefaultProjectRegistry();
    }
}
