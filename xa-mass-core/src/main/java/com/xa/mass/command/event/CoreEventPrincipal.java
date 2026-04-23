package com.xa.mass.command.event;

/**
 * Core runtime principal snapshot for event dispatch.
 */
public record CoreEventPrincipal(String clientId, String userId) {
}
