package com.dex.backend.matcher;

public enum MatcherWorkerState {
    STARTING,
    WAITING_FOR_INDEXER,
    IDLE,
    RUNNING,
    RESERVED,
    RETRYING,
    HALTED
}
