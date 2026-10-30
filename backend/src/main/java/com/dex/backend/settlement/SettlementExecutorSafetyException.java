package com.dex.backend.settlement;

public class SettlementExecutorSafetyException extends IllegalStateException {

    public SettlementExecutorSafetyException(String message) {
        super(message);
    }
}
