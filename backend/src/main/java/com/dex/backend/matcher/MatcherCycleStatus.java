package com.dex.backend.matcher;

public enum MatcherCycleStatus {
    WAITING_FOR_INDEXER,
    IDLE,
    TEMPORARILY_INELIGIBLE,
    RESERVED,
    ALREADY_RESERVED,
    STALE
}
