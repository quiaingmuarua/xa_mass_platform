package com.xa.mass.mock.command.runtime;

import com.google.gson.JsonObject;
import com.xa.mass.mock.command.model.ApiResponse;

public interface ResponseEnvProvider {

    void populate(ApiResponse<?> response, String targetApp, JsonObject request);
}
