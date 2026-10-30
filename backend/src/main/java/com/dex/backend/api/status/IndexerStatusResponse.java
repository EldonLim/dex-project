package com.dex.backend.api.status;

public record IndexerStatusResponse(
        boolean enabled,
        IndexerStatus state,
        String latestObservedBlockNumber,
        String lastIndexedBlockNumber,
        String indexingLagBlocks,
        int confirmationDepth,
        String lastSuccessfulSyncAt,
        String lastErrorAt,
        boolean paused
) {
}
