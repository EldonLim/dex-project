package com.dex.backend.api.error;

import java.util.Objects;

public record ApiFieldError(String field, String message) {

    public ApiFieldError {
        Objects.requireNonNull(field, "field must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
