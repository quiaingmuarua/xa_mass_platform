package com.xa.mass.command.event;

import java.util.List;

public interface MassEventRuntime {

    void register(CoreEventDescriptor descriptor, MassEventHandler handler);

    default void registerOrReplace(CoreEventDescriptor descriptor, MassEventHandler handler) {
        if (contains(descriptor.getEvent())) {
            throw new IllegalStateException("duplicate event register: " + descriptor.getEvent());
        }
        register(descriptor, handler);
    }

    CoreEventResponse dispatch(CoreEventRequest request, CoreEventPrincipal principal);

    CoreEventDescriptor getDescriptor(String event);

    List<CoreEventDescriptor> listDescriptors();

    boolean contains(String event);
}
