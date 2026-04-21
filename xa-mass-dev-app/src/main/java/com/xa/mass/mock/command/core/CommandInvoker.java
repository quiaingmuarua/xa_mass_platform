package com.xa.mass.mock.command.core;

import com.google.gson.JsonObject;
import com.xa.mass.mock.command.model.CommandResponse;

@FunctionalInterface
public interface CommandInvoker {
    CommandResponse<?> invoke(JsonObject json) throws Exception;
}
