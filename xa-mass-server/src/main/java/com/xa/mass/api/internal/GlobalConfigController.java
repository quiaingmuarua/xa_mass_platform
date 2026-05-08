package com.xa.mass.api.internal;

import com.xa.mass.api.config.GlobalConfig;
import com.xa.mass.api.model.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes small global-configuration endpoints used by demo pages.
 */
@RestController
@RequestMapping("/api/v1/runtime/config")
public class GlobalConfigController {

    @Autowired
    private GlobalConfig globalConfig;

    /**
     * Return all supported project codes.
     */
    @GetMapping("/projects")
    public ApiResponse<List<String>> getProjects() {
        try {
            List<String> projects = globalConfig.getAllProjects();
            return ApiResponse.success(projects);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to load projects: " + e.getMessage());
        }
    }
}
