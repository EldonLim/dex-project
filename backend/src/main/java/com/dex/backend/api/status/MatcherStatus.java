package com.dex.backend.api.status;

public enum MatcherStatus {
    DISABLED,
    STARTING,
    WAITING_FOR_INDEXER,
    IDLE,
    RUNNING,
    RESERVED,
    RETRYING,
    HALTED
}
