package com.dex.backend.api.status;

public enum ExecutorStatus {
    DISABLED,
    STARTING,
    IDLE,
    RUNNING,
    SIGNED,
    WAITING_FOR_RECONCILIATION,
    RETRYING,
    HALTED
}
