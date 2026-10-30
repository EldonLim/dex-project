package com.dex.backend.settlement;

class SettlementReceiptRetryException extends RuntimeException {

    SettlementReceiptRetryException(String message) {
        super(message);
    }
}
