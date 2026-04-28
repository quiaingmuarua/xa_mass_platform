package com.xa.mass.command.core;

import com.google.gson.JsonObject;
import com.xa.mass.command.model.CommandResponse;

@FunctionalInterface
public interface CommandInvoker {
    CommandResponse<?> invoke(JsonObject json) throws Exception;
}
