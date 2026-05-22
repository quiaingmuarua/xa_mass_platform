package com.xa.mass.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ApiResponse", description = "Standard server response envelope. Success uses code=0 and msg=ok.")
public class ApiResponse<T> {
    @Schema(description = "Application response code. 0 means success; HTTP errors use matching non-zero codes.", example = "0")
    private int code;
    @Schema(description = "Human-readable response message", example = "ok")
    private String msg;
    @Schema(description = "Typed response body. Null for errors.")
    private T data;

    public ApiResponse() {
    }

    public ApiResponse(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static <T> ApiResponse<T> error(int code, String msg) {
        return new ApiResponse<>(code, msg, null);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
