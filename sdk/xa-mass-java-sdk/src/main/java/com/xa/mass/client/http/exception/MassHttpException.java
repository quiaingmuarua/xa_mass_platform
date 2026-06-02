package com.xa.mass.client.http.exception;

public class MassHttpException extends MassClientException {
    private final String method;
    private final String path;
    private final int statusCode;
    private final String responseBody;

    public MassHttpException(String method, String path, int statusCode, String responseBody) {
        super("HTTP " + statusCode + " from " + method + " " + path + ": " + responseBody);
        this.method = method;
        this.path = path;
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public String method() {
        return method;
    }

    public String path() {
        return path;
    }

    public int statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }
}
