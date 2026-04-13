package com.xa.mass.base.jsondsl.builtin;

/**
 * mock 过程中的统一异常。
 *
 * 和 {@link com.xa.mass.base.jsondsl.parser.JsonDslParser} 进行 DSL 定义和解析。
 * 新标准提供更好的错误处理和异常管理机制。
 */
public class JsonDslException extends RuntimeException {
    public JsonDslException(String message) {
        super(message);
    }

    public JsonDslException(String message, Throwable cause) {
        super(message, cause);
    }
} 