package com.xa.mass.sdk;

import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;
import com.xa.mass.sdk.catalog.EventMetadata;

import java.util.List;

/**
 * SDK task-event resource operations.
 */
public interface EventOperations {

    /**
     * Dispatch an event through the SDK control-plane runtime.
     */
    EventResponse dispatchEvent(EventRequest request, EventPrincipal principal);

    /**
     * Register or replace event metadata.
     */
    void registerEvent(EventMetadata eventMetadata);

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
