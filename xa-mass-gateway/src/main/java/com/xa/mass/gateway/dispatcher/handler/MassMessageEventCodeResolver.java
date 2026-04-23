package com.xa.mass.gateway.dispatcher.handler;

import com.xa.mass.gateway.model.massMessage.MassMessage;

/**
 * Optional gateway-boundary hook for recovering canonical global event
 * metadata from a compatibility frame.
 *
 * <p>This is used only to enrich internal transport diagnostics such as
 * {@code Envelope.eventCode}. It must not be treated as a replacement for the
 * runtime event model, nor as a new tuple-routing surface.
 */
public interface MassMessageEventCodeResolver {

    /**
     * Returns the canonical global event code implied by the compatibility
     * frame, or {@code null} when the frame does not map to an SDK event.
     */
    String resolveEventCode(MassMessage message);
}
