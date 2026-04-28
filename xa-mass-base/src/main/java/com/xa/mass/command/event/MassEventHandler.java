package com.xa.mass.command.event;

@FunctionalInterface
public interface MassEventHandler {

    CoreEventResponse handle(CoreEventRequest request, CoreEventPrincipal principal);
}
