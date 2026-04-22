package com.xa.mass.starter.transport;

import com.xa.mass.gateway.dispatcher.handler.MassMessageHandler;
import com.xa.mass.gateway.model.enums.MessageType;

/**
 * Inbound handler registration metadata contributed by a transport binding.
 */
public record TransportInboundRoute(
        String project,
        MessageType messageType,
        String subMsgType,
        MassMessageHandler handler
) {
}
