package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.transport.model.TaskDispatchItem;

/**
 * Runtime-owned resolver for adapter-local dispatch route keys.
 *
 * <p>This keeps route-key assembly in transport runtime composition instead of
 * hard-coding it inside dispatch listeners. The resolved value is still only a
 * transport delivery address, not engine or business identity.</p>
 */
@FunctionalInterface
public interface TransportRouteKeyResolver {

    String resolveRouteKey(TaskDispatchBinding dispatchBinding, TaskDispatchItem payload);
}
