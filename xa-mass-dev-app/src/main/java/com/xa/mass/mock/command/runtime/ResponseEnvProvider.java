package com.xa.mass.mock.command.runtime;

import com.google.gson.JsonObject;
import com.xa.mass.mock.command.model.CommandResponse;

public interface ResponseEnvProvider {

    void populate(CommandResponse<?> response, String targetApp, JsonObject request);
}
