package com.xa.mass.sdk.catalog;

import com.xa.mass.base.model.TenantConstants;

import java.util.*;

/**
 * Public project metadata exposed through the SDK catalog APIs.
 */
public final class ProjectMetadata {

    private final String tenantId;
    private final String code;
    private final String name;
    private final String description;
    private final boolean enabled;
    private final String ownerPrincipalId;
    private final List<String> authorizedEventCodes;

    private ProjectMetadata(Builder builder) {
        this.tenantId = normalizeTenantId(builder.tenantId);
        this.code = requireNonBlank(builder.code, "code");
        this.name = requireNonBlank(builder.name, "name");
        this.description = builder.description != null ? builder.description : "";
        this.enabled = builder.enabled;
        this.ownerPrincipalId = normalizeNullable(builder.ownerPrincipalId);
        this.authorizedEventCodes = immutableEventCodes(builder.authorizedEventCodes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getOwnerPrincipalId() {
        return ownerPrincipalId;
    }

    public List<String> getAuthorizedEventCodes() {
        return authorizedEventCodes;
    }

    public List<String> getEventCodes() {
        return authorizedEventCodes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProjectMetadata that)) return false;
        return enabled == that.enabled
                && Objects.equals(tenantId, that.tenantId)
                && Objects.equals(code, that.code)
                && Objects.equals(name, that.name)
                && Objects.equals(description, that.description)
                && Objects.equals(ownerPrincipalId, that.ownerPrincipalId)
                && Objects.equals(authorizedEventCodes, that.authorizedEventCodes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, code, name, description, enabled, ownerPrincipalId, authorizedEventCodes);
    }

    @Override
    public String toString() {
        return "ProjectMetadata{" +
                "tenantId='" + tenantId + '\'' +
                ", code='" + code + '\'' +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", enabled=" + enabled +
                ", ownerPrincipalId='" + ownerPrincipalId + '\'' +
                ", authorizedEventCodes=" + authorizedEventCodes +
                '}';
    }

    private static String normalizeTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return TenantConstants.DEFAULT_TENANT_ID;
        }
        return tenantId.trim();
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static List<String> immutableEventCodes(Iterable<String> eventCodes) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (eventCodes != null) {
            for (String eventCode : eventCodes) {
                if (eventCode != null && !eventCode.isBlank()) {
                    ordered.add(eventCode.trim());
                }
            }
        }
        if (ordered.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(ordered));
    }

    public static final class Builder {
        private String tenantId = TenantConstants.DEFAULT_TENANT_ID;
        private String code;
        private String name;
        private String description;
        private boolean enabled = true;
        private String ownerPrincipalId;
        private List<String> authorizedEventCodes = Collections.emptyList();

        private Builder() {
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder code(String code) {
            this.code = code;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder ownerPrincipalId(String ownerPrincipalId) {
            this.ownerPrincipalId = ownerPrincipalId;
            return this;
        }

        public Builder authorizedEventCodes(List<String> authorizedEventCodes) {
            this.authorizedEventCodes = authorizedEventCodes != null ? authorizedEventCodes : Collections.emptyList();
            return this;
        }

        public Builder eventCodes(List<String> eventCodes) {
            return authorizedEventCodes(eventCodes);
        }

        public ProjectMetadata build() {
            return new ProjectMetadata(this);
        }
    }
}
