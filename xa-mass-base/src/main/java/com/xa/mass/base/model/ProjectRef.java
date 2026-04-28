package com.xa.mass.base.model;

import com.xa.mass.base.project.ProjectDescriptor;
import com.xa.mass.base.project.ProjectRegistry;

import java.util.Objects;

/**
 * Stable project binding carried by core aggregates.
 *
 * <p>The runtime persists the canonical project code while keeping a display
 * label available for logs and API views. Validation delegates to the runtime
 * project registry, which is seeded from built-in defaults and can be extended
 * by SDK resource registration.
 */
public class ProjectRef {

    private String code;
    private String name;

    public ProjectRef() {
    }

    public ProjectRef(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public static ProjectRef require(String code) {
        ProjectDescriptor project = ProjectRegistry.require(requireCode(code));
        return new ProjectRef(project.getCode(), project.getName());
    }

    public static String requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("project is required");
        }
        return code.trim();
    }

    public static boolean isValid(String code) {
        return code != null && !code.isBlank() && ProjectRegistry.isValidCode(code.trim());
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        ProjectRef normalized = require(code);
        this.code = normalized.code;
        this.name = normalized.name;
    }

    public String getName() {
        return name;
    }

    public boolean matchesCode(String projectCode) {
        return Objects.equals(code, projectCode);
    }

    @Override
    public String toString() {
        return code;
    }
}
