package com.xa.mass.gateway.dispatcher.handler;

import com.xa.mass.gateway.dispatcher.bridge.WorkerControlEventRequestBridge;
import com.xa.mass.gateway.dispatcher.event.EventGatewayBridge;
import com.xa.mass.gateway.model.massMessage.MassMessage;

import java.util.List;

/**
 * @deprecated Prefer {@link WorkerControlEventRequestBridge}. This type
 * remains only as a compatibility shim while gateway wiring moves to explicit
 * adapter bridge ports.
 */
@Deprecated(forRemoval = false)
public class WorkerControlEventBridgeHandler extends WorkerControlEventRequestBridge implements MassMessageHandler {

    public WorkerControlEventBridgeHandler(EventGatewayBridge bridge) {
        super(bridge);
    }

    @Override
    public List<MassMessage> handle(MassMessage msg) {
        return handleControlEventRequest(msg);
    }
}
