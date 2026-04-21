package com.xa.mass.mock.command.core;

import com.xa.mass.mock.command.model.CommandContext;

@FunctionalInterface
public interface NoArgCommandHandler<V> {
    V handle(CommandContext context) throws Exception;
}
