package com.xa.mass.gateway.model.massMessage;

/**
 * Lightweight protocol payload for transport-level acknowledgements such as
 * ping/pong and task receipt acknowledgements.
 *
 * <p>This is not an HTTP response envelope and not a business task result.
 */
public class MessageAckPayload {
    private int code;
    private String message;

    public MessageAckPayload() {
        this.code = 200;
        this.message = "success";
    }

    public MessageAckPayload(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
