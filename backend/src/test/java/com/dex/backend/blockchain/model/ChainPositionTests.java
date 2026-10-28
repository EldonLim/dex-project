package com.dex.backend.blockchain.model;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChainPositionTests {

    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger NEGATIVE_ONE = BigInteger.valueOf(-1);

    @Test
    void acceptsZeroComponents() {
        ChainPosition position = new ChainPosition(ZERO, ZERO, ZERO);

        assertEquals(ZERO, position.blockNumber());
        assertEquals(ZERO, position.transactionIndex());
        assertEquals(ZERO, position.logIndex());
    }

    @Test
    void rejectsEachNullComponent() {
        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new ChainPosition(null, ZERO, ZERO)
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new ChainPosition(ZERO, null, ZERO)
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new ChainPosition(ZERO, ZERO, null)
                )
        );
    }

    @Test
    void rejectsEachNegativeComponent() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new ChainPosition(NEGATIVE_ONE, ZERO, ZERO)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new ChainPosition(ZERO, NEGATIVE_ONE, ZERO)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new ChainPosition(ZERO, ZERO, NEGATIVE_ONE)
                )
        );
    }

    @Test
    void sortsByBlockThenTransactionThenLogIndex() {
        ChainPosition first = position(10, 5, 9);
        ChainPosition second = position(11, 0, 0);
        ChainPosition third = position(11, 0, 1);
        ChainPosition fourth = position(11, 1, 0);
        ChainPosition fifth = position(12, 0, 0);

        List<ChainPosition> actual = new ArrayList<>(
                List.of(fourth, fifth, third, first, second)
        );

        actual.sort(null); // Uses ChainPosition.compareTo()

        assertEquals(
                List.of(first, second, third, fourth, fifth),
                actual
        );
    }

    @Test
    void identicalComponentsCompareAsZero() {
        ChainPosition first = position(10, 2, 3);
        ChainPosition second = position(10, 2, 3);

        assertEquals(0, first.compareTo(second));
        assertEquals(first, second);
    }

    @Test
    void rejectsComparisonWithNull() {
        ChainPosition position = position(10, 2, 3);

        assertThrows(
                NullPointerException.class,
                () -> position.compareTo(null)
        );
    }

    @Test
    void preservesValuesLargerThanLongMaxValue() {
        BigInteger large = BigInteger.valueOf(Long.MAX_VALUE)
                .add(BigInteger.ONE);

        ChainPosition position =
                new ChainPosition(large, large, large);

        assertEquals(large, position.blockNumber());
        assertEquals(large, position.transactionIndex());
        assertEquals(large, position.logIndex());
    }

    @Test
    void comparesLargeValuesCorrectlyForEveryComponent() {
        BigInteger maxLong = BigInteger.valueOf(Long.MAX_VALUE);
        BigInteger aboveMax = maxLong.add(BigInteger.ONE);

        // Crossing Long.MAX_VALUE catches accidental conversion to long.
        assertBefore(
                new ChainPosition(maxLong, ZERO, ZERO),
                new ChainPosition(aboveMax, ZERO, ZERO)
        );

        assertBefore(
                new ChainPosition(ZERO, maxLong, ZERO),
                new ChainPosition(ZERO, aboveMax, ZERO)
        );

        assertBefore(
                new ChainPosition(ZERO, ZERO, maxLong),
                new ChainPosition(ZERO, ZERO, aboveMax)
        );

        // These differ by 2^64; converting to long would lose the difference.
        BigInteger muchLarger = aboveMax.add(BigInteger.ONE.shiftLeft(64));

        assertBefore(
                new ChainPosition(aboveMax, ZERO, ZERO),
                new ChainPosition(muchLarger, ZERO, ZERO)
        );

        assertBefore(
                new ChainPosition(ZERO, aboveMax, ZERO),
                new ChainPosition(ZERO, muchLarger, ZERO)
        );

        assertBefore(
                new ChainPosition(ZERO, ZERO, aboveMax),
                new ChainPosition(ZERO, ZERO, muchLarger)
        );
    }

    private static ChainPosition position(
            long block,
            long transaction,
            long log
    ) {
        return new ChainPosition(
                BigInteger.valueOf(block),
                BigInteger.valueOf(transaction),
                BigInteger.valueOf(log)
        );
    }

    private static void assertBefore(
            ChainPosition earlier,
            ChainPosition later
    ) {
        assertTrue(
                earlier.compareTo(later) < 0,
                () -> earlier + " should precede " + later
        );

        assertTrue(
                later.compareTo(earlier) > 0,
                () -> later + " should follow " + earlier
        );
    }
}