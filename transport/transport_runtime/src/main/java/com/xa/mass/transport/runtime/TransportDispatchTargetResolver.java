package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;

/**
 * Resolves an already assigned binding to a transport delivery target.
 *
 * <p>This is supplied by runtime assembly so transport delivery does not read
 * worker runtime state or perform a second worker-selection stage.</p>
 */
@FunctionalInterface
public interface TransportDispatchTargetResolver {

    TransportDispatchTarget resolve(TaskDispatchContext task, TaskDispatchBinding binding);
}
