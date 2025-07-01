package com.xa.mass.base.exception;

public class MessageProcessingException extends RuntimeException {
    public MessageProcessingException(String message) { super(message); }
    public MessageProcessingException(String message, Throwable cause) { super(message, cause); }
} 