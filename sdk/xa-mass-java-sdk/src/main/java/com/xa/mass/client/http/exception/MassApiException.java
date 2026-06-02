package com.xa.mass.client.http.exception;

public class MassApiException extends MassClientException {
    private final String method;
    private final String path;
    private final int httpStatusCode;
    private final int apiCode;
    private final String apiMessage;

    public MassApiException(String method, String path, int httpStatusCode, int apiCode, String apiMessage) {
        super("API error " + apiCode + " from " + method + " " + path + ": " + apiMessage);
        this.method = method;
        this.path = path;
        this.httpStatusCode = httpStatusCode;
        this.apiCode = apiCode;
        this.apiMessage = apiMessage;
    }

    public String method() {
        return method;
    }

    public String path() {
        return path;
    }

    public int httpStatusCode() {
        return httpStatusCode;
    }

    public int apiCode() {
        return apiCode;
    }

    public String apiMessage() {
        return apiMessage;
    }
}
