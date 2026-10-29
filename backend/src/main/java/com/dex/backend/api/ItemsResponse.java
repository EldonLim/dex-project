package com.dex.backend.api;

import java.util.List;
import java.util.Objects;

public record ItemsResponse<T>(List<T> items) {

    public ItemsResponse {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
    }
}
