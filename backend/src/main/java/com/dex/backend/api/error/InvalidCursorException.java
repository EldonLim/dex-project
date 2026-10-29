package com.dex.backend.api.error;

public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException() {
        super("Invalid pagination cursor");
    }
}
