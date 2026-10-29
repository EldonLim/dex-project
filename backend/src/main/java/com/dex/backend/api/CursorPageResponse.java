package com.dex.backend.api;

import java.util.List;
import java.util.Objects;

public record CursorPageResponse<T>(
        List<T> items,
        String nextCursor
) {

    public CursorPageResponse {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
    }
}
