package com.dex.backend.api.status;

import com.dex.backend.config.BlockchainMode;

public record BackendStatusResponse(
        BackendOperationalStatus status,
        BlockchainMode blockchainMode,
        DeploymentStatusResponse deployment,
        IndexerStatusResponse indexer,
        MatcherStatusResponse matcher,
        ExecutorStatusResponse executor
) {
}
