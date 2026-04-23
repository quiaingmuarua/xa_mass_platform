package com.xa.mass.command.model;


public class CommandException extends RuntimeException {
    private final ErrorCode errorCode;

    public CommandException(ErrorCode errorCode) {
        super(errorCode.defaultMessage);
        this.errorCode = errorCode;
    }

    public CommandException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }



}
