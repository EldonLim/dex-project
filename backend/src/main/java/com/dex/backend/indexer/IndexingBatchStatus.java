package com.dex.backend.indexer;

public enum IndexingBatchStatus {
    PROCESSED,
    UP_TO_DATE,
    WAITING_FOR_CONFIRMATIONS,
    PAUSED
}
