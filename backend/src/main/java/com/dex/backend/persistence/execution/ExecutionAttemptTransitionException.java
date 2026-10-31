package com.dex.backend.persistence.execution;

public class ExecutionAttemptTransitionException extends IllegalStateException {

    public ExecutionAttemptTransitionException(String message) {
        super(message);
    }
}
