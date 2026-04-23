package com.xa.mass.command.event;

import java.util.List;

public interface MassEventRuntime {

    void register(CoreEventDescriptor descriptor, MassEventHandler handler);

    CoreEventResponse dispatch(CoreEventRequest request, CoreEventPrincipal principal);

    CoreEventDescriptor getDescriptor(String event);

    List<CoreEventDescriptor> listDescriptors();

    boolean contains(String event);
}
