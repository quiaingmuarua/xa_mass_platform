package com.xa.mass.sdk;

import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;
import com.xa.mass.sdk.event.EventDefinition;

import java.util.List;

/**
 * Transport-neutral SDK event capability operations.
 */
public interface EventOperations {

    /**
     * Register the single source-of-truth SDK event definition.
     */
    void registerEventDefinition(EventDefinition definition);

    /**
     * Dispatch an event through the SDK control-plane runtime.
     */
    EventResponse dispatchEvent(EventRequest request, EventPrincipal principal);

    /**
     * Register or replace multiple event definitions.
     */
    default void registerEventDefinitions(List<EventDefinition> definitions) {
        if (definitions == null) {
            return;
        }
        definitions.forEach(this::registerEventDefinition);
    }

    List<EventDefinition> listEvents();

    EventDefinition getEvent(String eventCode);

    default boolean hasEvent(String eventCode) {
        return getEvent(eventCode) != null;
    }
}
