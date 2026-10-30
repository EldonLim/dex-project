package com.dex.backend.matcher;

public class MatcherSafetyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MatcherSafetyException(String message) {
        super(message);
    }

    public MatcherSafetyException(String message, Throwable cause) {
        super(message, cause);
    }
}
