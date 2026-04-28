package com.xa.mass.base.project;

import java.util.Objects;

/**
 * Runtime project descriptor used by core validation and project bindings.
 */
public final class ProjectDescriptor {

    private final String code;
    private final String name;
    private final boolean enabled;

    ProjectDescriptor(String code, String name, boolean enabled) {
        this.code = Objects.requireNonNull(code, "code");
        this.name = Objects.requireNonNull(name, "name");
        this.enabled = enabled;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProjectDescriptor that)) return false;
        return enabled == that.enabled
                && Objects.equals(code, that.code)
                && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, name, enabled);
    }

    @Override
    public String toString() {
        return "ProjectDescriptor{" +
                "code='" + code + '\'' +
                ", name='" + name + '\'' +
                ", enabled=" + enabled +
                '}';
    }
}
