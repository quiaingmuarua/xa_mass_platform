package com.xa.mass.sdk.event;

import com.xa.mass.sdk.catalog.EventMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Single SDK event registration unit.
 *
 * <p>The definition is the source of truth for SDK-visible metadata, project
 * scope, and the optional direct runtime handler. When {@link #getHandler()}
 * is {@code null}, the event is treated as a task-creation style catalog event.
 */
public final class SdkEventDefinition {

    private final EventMetadata metadata;
    private final List<String> projectCodes;
    private final SdkEventHandler handler;

    private SdkEventDefinition(Builder builder) {
        this.metadata = Objects.requireNonNull(builder.metadata, "metadata");
        this.projectCodes = immutableProjectCodes(builder.projectCodes);
        this.handler = builder.handler;
    }

    public static Builder builder() {
        return new Builder();
    }

    public EventMetadata getMetadata() {
        return metadata;
    }

    public String getEventCode() {
        return metadata.getCode();
    }

    public List<String> getProjectCodes() {
        return projectCodes;
    }

    public SdkEventHandler getHandler() {
        return handler;
    }

    public boolean hasHandler() {
        return handler != null;
    }

    private static List<String> immutableProjectCodes(Iterable<String> projectCodes) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (projectCodes != null) {
            for (String projectCode : projectCodes) {
                if (projectCode != null && !projectCode.isBlank()) {
                    normalized.add(projectCode.trim());
                }
            }
        }
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(normalized));
    }

    public static final class Builder {
        private EventMetadata metadata;
        private List<String> projectCodes = Collections.emptyList();
        private SdkEventHandler handler;

        private Builder() {
        }

        public Builder metadata(EventMetadata metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder projectCodes(List<String> projectCodes) {
            this.projectCodes = projectCodes != null ? projectCodes : Collections.emptyList();
            return this;
        }

        public Builder handler(SdkEventHandler handler) {
            this.handler = handler;
            return this;
        }

        public SdkEventDefinition build() {
            return new SdkEventDefinition(this);
        }
    }
}
