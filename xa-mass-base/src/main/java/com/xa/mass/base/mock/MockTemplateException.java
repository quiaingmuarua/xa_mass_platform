package com.xa.mass.base.mock;

/**
 * mock 过程中的统一异常。
 */
public class MockTemplateException extends RuntimeException {
    public MockTemplateException(String message) {
        super(message);
    }
    public MockTemplateException(String message, Throwable cause) {
        super(message, cause);
    }
} 