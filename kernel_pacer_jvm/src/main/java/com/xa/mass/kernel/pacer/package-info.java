/**
 * Fixed Java Kernel pacing policy and lifecycle.
 *
 * <p>The package exposes {@link KernelPacerRuntime} as its only external
 * production entry. The {@code result} and {@code dispatch} subpackages each
 * expose one module-internal bridge required by Java package visibility; all
 * policy, lane and application types remain package-private.</p>
 */
@NullMarked
package com.xa.mass.kernel.pacer;

import org.jspecify.annotations.NullMarked;
