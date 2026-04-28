package com.xa.mass.base.project;

import com.xa.mass.base.enums.Project;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory runtime project registry.
 *
 * <p>The legacy {@link Project} enum seeds the default projects, but runtime
 * project validation should depend on this registry so SDK-created projects can
 * become executable without changing the enum.
 */
public final class ProjectRegistry {

    private static final Map<String, ProjectDescriptor> PROJECTS = new ConcurrentHashMap<>();

    static {
        for (Project project : Project.values()) {
            register(project.getCode(), project.getName(), true);
        }
    }

    private ProjectRegistry() {
    }

    public static ProjectDescriptor register(String code, String name) {
        return register(code, name, true);
    }

    public static ProjectDescriptor register(String code, String name, boolean enabled) {
        String normalizedCode = requireCode(code);
        String normalizedName = name == null || name.isBlank() ? normalizedCode : name.trim();
        ProjectDescriptor descriptor = new ProjectDescriptor(normalizedCode, normalizedName, enabled);
        PROJECTS.put(normalizedCode, descriptor);
        return descriptor;
    }

    public static ProjectDescriptor find(String code) {
        String normalizedCode = normalizeCode(code);
        if (normalizedCode == null) {
            return null;
        }
        return PROJECTS.get(normalizedCode);
    }

    public static ProjectDescriptor require(String code) {
        String normalizedCode = requireCode(code);
        ProjectDescriptor descriptor = PROJECTS.get(normalizedCode);
        if (descriptor == null || !descriptor.isEnabled()) {
            throw new IllegalArgumentException("Unsupported project code: " + normalizedCode);
        }
        return descriptor;
    }

    public static boolean isValidCode(String code) {
        ProjectDescriptor descriptor = find(code);
        return descriptor != null && descriptor.isEnabled();
    }

    public static List<String> listProjectCodes() {
        return PROJECTS.values().stream()
                .filter(ProjectDescriptor::isEnabled)
                .sorted(Comparator.comparing(ProjectDescriptor::getCode))
                .map(ProjectDescriptor::getCode)
                .toList();
    }

    public static List<String> listProjectNames() {
        return PROJECTS.values().stream()
                .filter(ProjectDescriptor::isEnabled)
                .sorted(Comparator.comparing(ProjectDescriptor::getCode))
                .map(ProjectDescriptor::getName)
                .toList();
    }

    private static String requireCode(String code) {
        String normalizedCode = normalizeCode(code);
        if (normalizedCode == null) {
            throw new IllegalArgumentException("project is required");
        }
        return normalizedCode;
    }

    private static String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return code.trim();
    }
}
