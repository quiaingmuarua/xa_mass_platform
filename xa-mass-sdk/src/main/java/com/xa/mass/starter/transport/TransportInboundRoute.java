package com.xa.mass.starter.transport;

import com.xa.mass.gateway.dispatcher.handler.MassMessageHandler;
import com.xa.mass.gateway.model.enums.MessageType;

/**
 * Inbound protocol-frame registration metadata contributed by a transport binding.
 *
 * <p>The tuple {@code messageType + subMsgType} is a transport compatibility
 * classification only. Business and control capabilities must be modeled by
 * globally unique SDK event codes instead of adding new tuple identities here.
 */
public record TransportInboundRoute(
        MessageType messageType,
        String subMsgType,
        MassMessageHandler handler
) {
}
