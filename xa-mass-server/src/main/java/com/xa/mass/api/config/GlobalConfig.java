package com.xa.mass.api.config;

import com.xa.mass.sdk.catalog.SdkMetadataCatalog;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Small holder for globally shared API-side configuration.
 */
@Component
public class GlobalConfig {

    private final SdkMetadataCatalog metadataCatalog;

    public GlobalConfig(SdkMetadataCatalog metadataCatalog) {
        this.metadataCatalog = metadataCatalog;
    }

    /**
     * Return all registered control-plane project codes.
     */
    public List<String> getAllProjects() {
        return metadataCatalog.listProjects().stream()
                .map(project -> project.getCode())
                .toList();
    }
}
