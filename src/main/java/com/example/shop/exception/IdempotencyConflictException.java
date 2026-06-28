package com.example.shop.exception;

import java.io.Serial;

public class IdempotencyConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public IdempotencyConflictException(String message) {
        super(message);
    }

    public IdempotencyConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
