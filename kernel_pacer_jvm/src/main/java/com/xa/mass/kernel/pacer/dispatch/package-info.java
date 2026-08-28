/**
 * Fixed Dispatch lanes, policy, internal mechanisms and opaque references.
 *
 * <p>A mechanism exists only where a legal transition composes owners or must
 * protect an exact fence. Policy may call a bounded owner directly when that
 * call already belongs to its decision; score coordinates remain opaque.</p>
 */
package com.xa.mass.kernel.pacer.dispatch;
