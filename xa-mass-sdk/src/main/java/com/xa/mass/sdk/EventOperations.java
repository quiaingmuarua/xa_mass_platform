package com.xa.mass.sdk;

import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;
import com.xa.mass.sdk.event.SdkEventDefinition;

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
     * Register or replace multiple event definitions.
     */
    default void registerEventDefinitions(List<SdkEventDefinition> definitions) {
        if (definitions == null) {
            return;
        }
        definitions.forEach(this::registerEventDefinition);
    }

    List<SdkEventDefinition> listEvents();

    SdkEventDefinition getEvent(String eventCode);

    default boolean hasEvent(String eventCode) {
        return getEvent(eventCode) != null;
    }
}
