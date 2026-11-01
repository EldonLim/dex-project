package com.dex.backend.blockchain.execution;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record SettlementBatchRequest(
        String deploymentId,
        BigInteger marketId,
        List<SettlementLeg> legs
) {

    public static final int MAX_LEGS = 10;

    public SettlementBatchRequest {
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
            throw new IllegalArgumentException("legs must contain between 1 and 10 settlements");
        }

        Set<OrderPair> pairs = new HashSet<>();
        for (SettlementLeg leg : legs) {
            Objects.requireNonNull(leg, "legs must not contain null values");
            if (!pairs.add(new OrderPair(leg.buyOrderId(), leg.sellOrderId()))) {
                throw new IllegalArgumentException(
                        "A buy/sell pair must not appear more than once in a batch"
                );
            }
        }
    }

    private record OrderPair(BigInteger buyOrderId, BigInteger sellOrderId) {
    }
}
