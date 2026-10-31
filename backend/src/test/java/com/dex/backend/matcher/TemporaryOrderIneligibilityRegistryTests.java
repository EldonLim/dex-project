package com.dex.backend.matcher;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporaryOrderIneligibilityRegistryTests {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final TemporaryOrderIneligibilityRegistry registry =
            new TemporaryOrderIneligibilityRegistry(Duration.ofSeconds(30), clock);

    @Test
    void exclusionsAreDeploymentScopedAndSnapshotsAreImmutable() {
        registry.markTemporarilyIneligible("deployment-a", BigInteger.ONE);
        registry.markTemporarilyIneligible("deployment-b", BigInteger.TWO);

        Set<BigInteger> deploymentA = registry.activeOrderIds("deployment-a");

        assertEquals(Set.of(BigInteger.ONE), deploymentA);
        assertEquals(Set.of(BigInteger.TWO), registry.activeOrderIds("deployment-b"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> deploymentA.add(BigInteger.TEN)
        );
    }

    @Test
    void exclusionExpiresAtItsDeadline() {
        registry.markTemporarilyIneligible("deployment", BigInteger.ONE);

        clock.advance(Duration.ofSeconds(29));
        assertEquals(Set.of(BigInteger.ONE), registry.activeOrderIds("deployment"));

        clock.advance(Duration.ofSeconds(1));
        assertTrue(registry.activeOrderIds("deployment").isEmpty());
    }

    @Test
    void remarkingAnOrderRefreshesItsCooldown() {
        registry.markTemporarilyIneligible("deployment", BigInteger.ONE);
        clock.advance(Duration.ofSeconds(20));
        registry.markTemporarilyIneligible("deployment", BigInteger.ONE);

        clock.advance(Duration.ofSeconds(20));
        assertEquals(Set.of(BigInteger.ONE), registry.activeOrderIds("deployment"));

        clock.advance(Duration.ofSeconds(10));
        assertTrue(registry.activeOrderIds("deployment").isEmpty());
    }

    @Test
    void rejectsInvalidKeysAndDurations() {
        assertThrows(
                NullPointerException.class,
                () -> registry.markTemporarilyIneligible(null, BigInteger.ONE)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.markTemporarilyIneligible(" ", BigInteger.ONE)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.markTemporarilyIneligible(
                        "deployment",
                        BigInteger.valueOf(-1)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new TemporaryOrderIneligibilityRegistry(
                        Duration.ZERO,
                        clock
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new TemporaryOrderIneligibilityRegistry(
                        Duration.ofSeconds(-1),
                        clock
                )
        );
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Only UTC is supported");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
