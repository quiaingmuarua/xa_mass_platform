package com.xa.mass.mock.command.core;

import com.google.gson.JsonObject;
import com.xa.mass.mock.command.model.ApiResponse;

@FunctionalInterface
public interface CommandInvoker {
    ApiResponse<?> invoke(JsonObject json) throws Exception;
}
