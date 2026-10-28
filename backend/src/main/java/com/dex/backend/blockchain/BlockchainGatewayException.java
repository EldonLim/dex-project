package com.dex.backend.blockchain;

public class BlockchainGatewayException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean retryable;

    public BlockchainGatewayException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public BlockchainGatewayException(
            String message,
            boolean retryable,
            Throwable cause
    ) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}