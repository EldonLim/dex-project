package com.dex.backend.api.status;

public record ExecutorStatusResponse(
        boolean enabled,
        ExecutorStatus state
) {
}
