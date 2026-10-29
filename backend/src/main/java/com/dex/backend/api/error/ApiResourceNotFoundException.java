package com.dex.backend.api.error;

public class ApiResourceNotFoundException extends RuntimeException {

    public ApiResourceNotFoundException() {
        super("API resource not found");
    }
}
