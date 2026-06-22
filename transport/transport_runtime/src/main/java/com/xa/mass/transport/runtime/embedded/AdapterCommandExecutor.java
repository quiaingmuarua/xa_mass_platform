package com.xa.mass.transport.runtime.embedded;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.runtime.delivery.DispatchRoutingItem;

import java.util.List;

/**
 * Embedded Java adapter-local executor for assigned dispatch items.
 *
 * <p>This contract owns only the final-hop command attempt. Adapter identity,
 * transport hint, protocol label, session evidence, and diagnostics belong to
 * transport binding and adapter-local runtime components, not to this executor.
 */
@FunctionalInterface
public interface AdapterCommandExecutor {

    List<DispatchOutcome> dispatch(List<DispatchRoutingItem> items);
}
