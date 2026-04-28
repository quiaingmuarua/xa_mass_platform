package com.xa.mass.command.core;

import com.xa.mass.command.model.CommandContext;

@FunctionalInterface
public interface NoArgCommandHandler<V> {
    V handle(CommandContext context) throws Exception;
}
