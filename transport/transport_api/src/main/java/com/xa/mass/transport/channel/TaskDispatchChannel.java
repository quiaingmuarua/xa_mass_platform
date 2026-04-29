package com.xa.mass.transport.channel;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.TransportDispatchEnvelope;

import java.util.List;

/**
 * Transport-neutral channel for dispatching logical task items to workers.
 */
public interface TaskDispatchChannel {

    List<DispatchOutcome> dispatchEnvelopes(List<TransportDispatchEnvelope> envelopes);
}
