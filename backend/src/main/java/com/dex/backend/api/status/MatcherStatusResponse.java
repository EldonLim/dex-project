package com.dex.backend.api.status;

public record MatcherStatusResponse(
        boolean enabled,
        MatcherStatus state
) {
}
