package com.xa.mass.client.http;

import com.xa.mass.client.UnstableApi;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

@UnstableApi("Raw HTTP stream response; prefer typed task archive APIs.")
public final class MassHttpStreamResponse implements AutoCloseable {
    private final int statusCode;
    private final String contentType;
    private final String contentEncoding;
    private final String contentDisposition;
    private final InputStream body;

    public MassHttpStreamResponse(int statusCode,
                                  String contentType,
                                  String contentEncoding,
                                  String contentDisposition,
                                  InputStream body) {
        this.statusCode = statusCode;
        this.contentType = contentType == null ? "" : contentType;
        this.contentEncoding = contentEncoding == null ? "" : contentEncoding;
        this.contentDisposition = contentDisposition == null ? "" : contentDisposition;
        this.body = Objects.requireNonNull(body, "body is required");
    }

    public int statusCode() {
        return statusCode;
    }

    public String contentType() {
        return contentType;
    }

    public String contentEncoding() {
        return contentEncoding;
    }

    public String contentDisposition() {
        return contentDisposition;
    }

    public InputStream body() {
        return body;
    }

    @Override
    public void close() throws IOException {
        body.close();
    }
}
