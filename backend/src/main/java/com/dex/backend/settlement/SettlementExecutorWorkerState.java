package com.dex.backend.settlement;

public enum SettlementExecutorWorkerState {
    STARTING,
    IDLE,
    RUNNING,
    SIGNED,
    WAITING_FOR_RECONCILIATION,
    RETRYING,
    HALTED
}
