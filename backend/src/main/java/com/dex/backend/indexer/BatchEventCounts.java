package com.dex.backend.indexer;

record BatchEventCounts(int applied, int replayed) {

    BatchEventCounts {
        if (applied < 0 || replayed < 0) {
            throw new IllegalArgumentException("Event counts must be non-negative");
        }
    }
}
