package com.dex.backend.indexer;

import com.dex.backend.blockchain.model.BlockRange;

import java.util.Objects;
import java.util.Optional;

public record IndexingBatchResult(
        IndexingBatchStatus status,
        Optional<BlockRange> range,
        int appliedEvents,
        int replayedEvents
) {

    public IndexingBatchResult {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(range, "range must not be null");

        if (appliedEvents < 0 || replayedEvents < 0) {
            throw new IllegalArgumentException("Event counts must be non-negative");
        }

        if (status == IndexingBatchStatus.PROCESSED && range.isEmpty()) {
            throw new IllegalArgumentException("Processed results require a block range");
        }

        if (status != IndexingBatchStatus.PROCESSED
                && (range.isPresent() || appliedEvents != 0 || replayedEvents != 0)) {
            throw new IllegalArgumentException(
                    "Non-processed results cannot contain a range or event counts"
            );
        }
    }

    public static IndexingBatchResult processed(
            BlockRange range,
            int appliedEvents,
            int replayedEvents
    ) {
        return new IndexingBatchResult(
                IndexingBatchStatus.PROCESSED,
                Optional.of(range),
                appliedEvents,
                replayedEvents
        );
    }

    public static IndexingBatchResult withoutRange(IndexingBatchStatus status) {
        if (status == IndexingBatchStatus.PROCESSED) {
            throw new IllegalArgumentException("Use processed() for processed results");
        }

        return new IndexingBatchResult(status, Optional.empty(), 0, 0);
    }
}
