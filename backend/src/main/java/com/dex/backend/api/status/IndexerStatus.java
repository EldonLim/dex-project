package com.dex.backend.api.status;

public enum IndexerStatus {
    DISABLED,
    STARTING,
    RUNNING,
    SYNCING,
    UP_TO_DATE,
    RETRYING,
    PAUSED
}
