package com.xa.mass.command.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Process-local command response envelope.
 *
 * <p>This is intentionally separate from HTTP API responses.
 */
public class CommandResponse<T> {
    public String status;
    public int code;
    public String message;
    public T data;
    public int duration;
    public Map<String, String> env = new HashMap<>();
    public Map<String, Object> forward = new HashMap<>();

    public CommandResponse(String status, T data) {
        this.status = status;
        this.code = 200;
        this.data = data;
    }

    public CommandResponse(int code, String message) {
        this.status = "error";
        this.code = code;
        this.message = message;
    }

    public CommandResponse() {
    }

    public static CommandResponse<?> fromException(CommandException e) {
        return new CommandResponse<>(e.getErrorCode().code, e.getMessage());
    }

    public static CommandResponse<?> fromException(Exception e) {
        if (e instanceof CommandException exception) {
            return new CommandResponse<>(exception.getErrorCode().code, e.getMessage());
        }
        return new CommandResponse<>(ErrorCode.UNKNOWN_ERROR.code, e.getMessage());
    }

    public void setDuration(long duration) {
        this.duration = (int) duration;
    }

    public void copyForwardFromRequest(JsonObject request) {
        if (request == null || !request.has("forward") || !request.get("forward").isJsonObject()) {
            return;
        }
        JsonObject forwardJson = request.getAsJsonObject("forward");
        for (Map.Entry<String, JsonElement> entry : forwardJson.entrySet()) {
            this.forward.put(entry.getKey(), entry.getValue());
        }
    }

    public void addEnv(String key, String value) {
        this.env.put(key, value);
    }

    public static <T> CommandResponse<T> success(T data) {
        CommandResponse<T> resp = new CommandResponse<>();
        resp.code = 200;
        resp.status = "ok";
        resp.data = data;
        return resp;
    }

    public boolean isSuccess() {
        return "ok".equals(status) && code == 200;
    }

    public String getMessage() {
        return message;
    }

    public int getCode() {
        return code;
    }

    public T getData() {
        return data;
    }
}
