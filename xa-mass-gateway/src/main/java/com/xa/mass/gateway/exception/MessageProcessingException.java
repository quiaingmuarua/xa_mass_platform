package com.xa.mass.gateway.exception;

public class MessageProcessingException extends RuntimeException {
    public MessageProcessingException(String message) { super(message); }
    public MessageProcessingException(String message, Throwable cause) { super(message, cause); }
} 