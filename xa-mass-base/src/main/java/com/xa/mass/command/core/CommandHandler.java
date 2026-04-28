package com.xa.mass.command.core;

import com.xa.mass.command.model.CommandContext;

@FunctionalInterface
public interface CommandHandler<T, V> {
    V handle(T request, CommandContext context) throws Exception;
}
