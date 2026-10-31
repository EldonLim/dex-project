package com.dex.backend.matcher;

import com.dex.backend.blockchain.model.EthereumAddress;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Set;

public record MatchPreflightResult(
        EthereumAddress executorAddress,
        Set<BigInteger> ineligibleOrderIds
) {

    public MatchPreflightResult {
        Objects.requireNonNull(
                ineligibleOrderIds,
                "ineligibleOrderIds must not be null"
        );
        ineligibleOrderIds = Set.copyOf(ineligibleOrderIds);

        if (executorAddress == null && ineligibleOrderIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "An ineligible result must identify at least one order"
            );
        }
        if (executorAddress != null && !ineligibleOrderIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "An eligible result must not identify ineligible orders"
            );
        }
        if (ineligibleOrderIds.stream().anyMatch(value ->
                value == null || value.signum() < 0)) {
            throw new IllegalArgumentException(
                    "Ineligible order IDs must be non-null and non-negative"
            );
        }
    }

    public static MatchPreflightResult eligible(
            EthereumAddress executorAddress
    ) {
        return new MatchPreflightResult(
                Objects.requireNonNull(
                        executorAddress,
                        "executorAddress must not be null"
                ),
                Set.of()
        );
    }

    public static MatchPreflightResult ineligible(Set<BigInteger> orderIds) {
        return new MatchPreflightResult(null, orderIds);
    }

    public boolean eligible() {
        return executorAddress != null;
    }
}
