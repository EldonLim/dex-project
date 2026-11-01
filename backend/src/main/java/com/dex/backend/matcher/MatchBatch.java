package com.dex.backend.matcher;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

public record MatchBatch(
        String deploymentId,
        BigInteger marketId,
        List<MatchProposal> legs
) {

    public static final int MAX_LEGS = 10;

    public MatchBatch {
        Objects.requireNonNull(deploymentId, "deploymentId must not be null");
        Objects.requireNonNull(marketId, "marketId must not be null");
        Objects.requireNonNull(legs, "legs must not be null");
        legs = List.copyOf(legs);

        if (deploymentId.isBlank() || deploymentId.length() > 128) {
            throw new IllegalArgumentException(
                    "deploymentId must be nonblank and at most 128 characters"
            );
        }
        if (marketId.signum() < 0) {
            throw new IllegalArgumentException("marketId must be non-negative");
        }
        if (legs.isEmpty() || legs.size() > MAX_LEGS) {
            throw new IllegalArgumentException("legs must contain between 1 and 10 matches");
        }
        for (MatchProposal leg : legs) {
            Objects.requireNonNull(leg, "legs must not contain null values");
            if (!deploymentId.equals(leg.deploymentId())
                    || !marketId.equals(leg.marketId())) {
                throw new IllegalArgumentException(
                        "Every match leg must belong to the batch deployment and market"
                );
            }
        }
    }

    public static MatchBatch single(MatchProposal proposal) {
        Objects.requireNonNull(proposal, "proposal must not be null");
        return new MatchBatch(
                proposal.deploymentId(),
                proposal.marketId(),
                List.of(proposal)
        );
    }
}
