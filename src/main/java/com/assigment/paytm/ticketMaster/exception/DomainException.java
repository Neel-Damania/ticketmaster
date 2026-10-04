package com.assigment.paytm.ticketMaster.exception;

public class DomainException extends RuntimeException {
    private final String code;
    private final int httpStatus;

    public DomainException(String code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
