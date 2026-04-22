package com.xa.mass.api.config;

import com.xa.mass.base.enums.Project;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Small holder for globally shared API-side configuration.
 */
@Component
public class GlobalConfig {

    /**
     * Return all supported project codes.
     */
    public List<String> getAllProjects() {
        return Project.getAllCodes();
    }
}
