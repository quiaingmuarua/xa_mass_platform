package com.xa.mass.mock.command.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

public class ApiResponse<T> {
    public String status;
    public int code;
    public String message;
    public T data;
    public int duration;
    public Map<String, String> env = new HashMap<>();
    public Map<String, Object> forward = new HashMap<>();

    public ApiResponse(String status, T data) {
        this.status = status;
        this.code = 200;
        this.data = data;
    }

    public ApiResponse(int code, String message) {
        this.status = "error";
        this.code = code;
        this.message = message;
    }

    public ApiResponse() {
    }

    public static ApiResponse<?> fromException(CommandException e) {
        return new ApiResponse<>(e.getErrorCode().code, e.getMessage());
    }

    public static ApiResponse<?> fromException(Exception e) {
        if (e instanceof CommandException exception) {
            return new ApiResponse<>(exception.getErrorCode().code, e.getMessage());
        }
        return new ApiResponse<>(ErrorCode.UNKNOWN_ERROR.code, e.getMessage());
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

    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> resp = new ApiResponse<>();
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
