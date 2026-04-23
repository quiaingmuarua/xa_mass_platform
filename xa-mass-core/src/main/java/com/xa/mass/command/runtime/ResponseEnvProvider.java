package com.xa.mass.command.runtime;

import com.google.gson.JsonObject;
import com.xa.mass.command.model.CommandResponse;

public interface ResponseEnvProvider {

    void populate(CommandResponse<?> response, String targetApp, JsonObject request);
}
