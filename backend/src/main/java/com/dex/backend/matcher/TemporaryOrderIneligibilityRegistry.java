package com.dex.backend.matcher;

import com.dex.backend.config.DexProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@Component
public class TemporaryOrderIneligibilityRegistry {

    private final Duration ineligibilityDuration;
    private final Clock clock;
    private final ConcurrentMap<OrderKey, Instant> expirations =
            new ConcurrentHashMap<>();

    @Autowired
    public TemporaryOrderIneligibilityRegistry(DexProperties properties) {
        this(properties.matcher().ineligibilityDuration(), Clock.systemUTC());
    }

    TemporaryOrderIneligibilityRegistry(
            Duration ineligibilityDuration,
            Clock clock
    ) {
        this.ineligibilityDuration = Objects.requireNonNull(
                ineligibilityDuration,
                "ineligibilityDuration must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");

        if (ineligibilityDuration.isZero() || ineligibilityDuration.isNegative()) {
            throw new IllegalArgumentException(
                    "ineligibilityDuration must be positive"
            );
        }
    }

    public void markTemporarilyIneligible(
            String deploymentId,
            BigInteger orderId
    ) {
        OrderKey key = orderKey(deploymentId, orderId);
        Instant newExpiration = clock.instant().plus(ineligibilityDuration);

        expirations.merge(
                key,
                newExpiration,
                (current, replacement) -> current.isAfter(replacement)
                        ? current
                        : replacement
        );
    }

    public Set<BigInteger> activeOrderIds(String deploymentId) {
        validateDeploymentId(deploymentId);
        Instant now = clock.instant();

        expirations.forEach((key, expiration) -> {
            if (!expiration.isAfter(now)) {
                expirations.remove(key, expiration);
            }
        });

        return expirations.entrySet().stream()
                .filter(entry -> entry.getKey().deploymentId().equals(deploymentId))
                .filter(entry -> entry.getValue().isAfter(now))
                .map(entry -> entry.getKey().orderId())
                .collect(Collectors.toUnmodifiableSet());
    }

    void clear() {
        expirations.clear();
    }

    private static OrderKey orderKey(
            String deploymentId,
            BigInteger orderId
    ) {
        validateDeploymentId(deploymentId);
        Objects.requireNonNull(orderId, "orderId must not be null");

        if (orderId.signum() < 0) {
            throw new IllegalArgumentException("orderId must be non-negative");
        }

        return new OrderKey(deploymentId, orderId);
    }

    private static void validateDeploymentId(String deploymentId) {
        Objects.requireNonNull(deploymentId, "deploymentId must not be null");

        if (deploymentId.isBlank() || deploymentId.length() > 128) {
            throw new IllegalArgumentException(
                    "deploymentId must be nonblank and at most 128 characters"
            );
        }
    }

    private record OrderKey(String deploymentId, BigInteger orderId) {
    }
}
