package com.xa.mass.api.config;

import com.xa.mass.sdk.catalog.ControlPlaneCatalog;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Small holder for globally shared API-side configuration.
 */
@Component
public class GlobalConfig {

    private final ControlPlaneCatalog catalog;

    public GlobalConfig(ControlPlaneCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Return all registered control-plane project codes.
     */
    public List<String> getAllProjects() {
        return catalog.listProjects().stream()
                .map(project -> project.getCode())
                .toList();
    }
}
