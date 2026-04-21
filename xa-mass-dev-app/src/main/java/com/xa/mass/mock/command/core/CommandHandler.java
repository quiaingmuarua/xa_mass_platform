package com.xa.mass.mock.command.core;

import com.xa.mass.mock.command.model.CommandContext;

@FunctionalInterface
public interface CommandHandler<T, V> {
    V handle(T request, CommandContext context) throws Exception;
}
