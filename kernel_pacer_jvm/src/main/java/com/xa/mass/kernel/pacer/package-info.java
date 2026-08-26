/**
 * Fixed Java Kernel pacing policy and lifecycle.
 *
 * <p>The package intentionally exposes only {@link KernelPacerRuntime}.
 * All policy, loop and application types remain package-private so callers
 * cannot assemble a partial scheduling runtime.</p>
 */
@NullMarked
package com.xa.mass.kernel.pacer;

import org.jspecify.annotations.NullMarked;
