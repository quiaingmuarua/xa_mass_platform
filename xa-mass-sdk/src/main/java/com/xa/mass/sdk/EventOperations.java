package com.xa.mass.sdk;

import com.xa.mass.sdk.catalog.EventMetadata;
import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;
import com.xa.mass.sdk.event.SdkEventDefinition;
import com.xa.mass.sdk.event.SdkEventHandler;

import java.util.List;

/**
 * SDK task-event resource operations.
 */
public interface EventOperations {

    /**
     * Register the single source-of-truth SDK event definition.
     */
    void registerEventDefinition(SdkEventDefinition definition);

    /**
     * Dispatch an event through the SDK control-plane runtime.
     */
    EventResponse dispatchEvent(EventRequest request, EventPrincipal principal);

    /**
     * Register or replace event metadata.
     */
    void registerEvent(EventMetadata eventMetadata);

    /**
     * Register a runtime-handled event that returns data directly.
     *
     * <p>The event metadata stays visible through the normal SDK metadata APIs,
     * while dispatch is handled by the provided runtime handler instead of task
     * creation.
     */
    void registerRuntimeEvent(EventMetadata eventMetadata, List<String> projectCodes, SdkEventHandler handler);

    default void registerRuntimeEvent(EventMetadata eventMetadata, SdkEventHandler handler) {
        registerRuntimeEvent(eventMetadata, List.of(), handler);
    }

    /**
     * Register or replace multiple event metadata entries.
     */
    default void registerEvents(List<EventMetadata> eventMetadataList) {
        if (eventMetadataList == null) {
            return;
        }
        eventMetadataList.forEach(this::registerEvent);
    }

    List<EventMetadata> listEvents();

    EventMetadata getEvent(String eventCode);

    default boolean hasEvent(String eventCode) {
        return getEvent(eventCode) != null;
    }
}
