package com.example.shop.exception;

import java.io.Serial;

public class ServiceUnavailableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
