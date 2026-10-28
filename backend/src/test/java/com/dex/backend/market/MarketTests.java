package com.dex.backend.market;

import com.dex.backend.blockchain.model.EthereumAddress;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarketTests {

    private static final EthereumAddress BASE =
            new EthereumAddress("0x" + "11".repeat(20));

    private static final EthereumAddress QUOTE =
            new EthereumAddress("0x" + "22".repeat(20));

    @Test
    void createsValidMarket() {
        Market market = validMarket();

        assertAll(
                () -> assertEquals("local-dex-001", market.deploymentId()),
                () -> assertEquals(BigInteger.ONE, market.marketId()),
                () -> assertEquals(BASE, market.baseTokenAddress()),
                () -> assertEquals(QUOTE, market.quoteTokenAddress()),
                () -> assertEquals("Apple", market.baseSymbol()),
                () -> assertEquals("usdToken", market.quoteSymbol()),
                () -> assertEquals(2, market.baseDecimals()),
                () -> assertEquals(6, market.quoteDecimals()),
                () -> assertEquals(
                        BigInteger.valueOf(1000),
                        market.baseLotSizeAtoms()
                ),
                () -> assertTrue(market.active())
        );
    }

    @Test
    void stripsSymbolsWithoutChangingCase() {
        Market market = new Market(
                "local-dex-001",
                BigInteger.ONE,
                BASE,
                QUOTE,
                " \tApple\u2003",
                "\nusdToken ",
                2,
                6,
                BigInteger.valueOf(1000),
                true
        );

        assertEquals("Apple", market.baseSymbol());
        assertEquals("usdToken", market.quoteSymbol());
    }

    @Test
    void rejectsSameTokenAddress() {
        EthereumAddress lowercase =
                new EthereumAddress("0x" + "ab".repeat(20));

        EthereumAddress uppercase =
                new EthereumAddress("0x" + "AB".repeat(20));

        assertThrows(
                IllegalArgumentException.class,
                () -> new Market(
                        "local-dex-001",
                        BigInteger.ONE,
                        lowercase,
                        uppercase,
                        "Apple",
                        "usdToken",
                        2,
                        6,
                        BigInteger.ONE,
                        true
                )
        );
    }

    @Test
    void rejectsNegativeMarketId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> market(
                        "local-dex-001",
                        BigInteger.valueOf(-1),
                        "Apple",
                        "usdToken",
                        2,
                        6,
                        BigInteger.ONE
                )
        );
    }

    @Test
    void rejectsBlankOrOversizedDeploymentId() {
        for (String id : List.of("", " \t\n", "d".repeat(129))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> market(
                            id,
                            BigInteger.ONE,
                            "Apple",
                            "usdToken",
                            2,
                            6,
                            BigInteger.ONE
                    )
            );
        }
    }

    @Test
    void rejectsBlankOrOversizedSymbols() {
        for (String symbol : List.of("", " \t\n", "s".repeat(33))) {
            assertAll(
                    () -> assertThrows(
                            IllegalArgumentException.class,
                            () -> market(
                                    "local-dex-001",
                                    BigInteger.ONE,
                                    symbol,
                                    "usdToken",
                                    2,
                                    6,
                                    BigInteger.ONE
                            )
                    ),
                    () -> assertThrows(
                            IllegalArgumentException.class,
                            () -> market(
                                    "local-dex-001",
                                    BigInteger.ONE,
                                    "Apple",
                                    symbol,
                                    2,
                                    6,
                                    BigInteger.ONE
                            )
                    )
            );
        }
    }

    @Test
    void rejectsDecimalsOutsideRange() {
        for (int decimals : new int[]{-1, 256}) {
            assertAll(
                    () -> assertThrows(
                            IllegalArgumentException.class,
                            () -> market(
                                    "local-dex-001",
                                    BigInteger.ONE,
                                    "Apple",
                                    "usdToken",
                                    decimals,
                                    6,
                                    BigInteger.ONE
                            )
                    ),
                    () -> assertThrows(
                            IllegalArgumentException.class,
                            () -> market(
                                    "local-dex-001",
                                    BigInteger.ONE,
                                    "Apple",
                                    "usdToken",
                                    2,
                                    decimals,
                                    BigInteger.ONE
                            )
                    )
            );
        }
    }

    @Test
    void acceptsBoundaryValues() {
        for (int decimals : new int[]{0, 255}) {
            Market market = market(
                    "d".repeat(128),
                    BigInteger.ZERO,
                    " " + "a".repeat(32) + " ",
                    " " + "B".repeat(32) + " ",
                    decimals,
                    decimals,
                    BigInteger.ONE
            );

            assertAll(
                    () -> assertEquals(128, market.deploymentId().length()),
                    () -> assertEquals(BigInteger.ZERO, market.marketId()),
                    () -> assertEquals("a".repeat(32), market.baseSymbol()),
                    () -> assertEquals("B".repeat(32), market.quoteSymbol()),
                    () -> assertEquals(decimals, market.baseDecimals()),
                    () -> assertEquals(decimals, market.quoteDecimals())
            );
        }
    }

    @Test
    void rejectsZeroOrNegativeLotSize() {
        for (BigInteger size : List.of(
                BigInteger.ZERO,
                BigInteger.valueOf(-1)
        )) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> market(
                            "local-dex-001",
                            BigInteger.ONE,
                            "Apple",
                            "usdToken",
                            2,
                            6,
                            size
                    )
            );
        }
    }

    @Test
    void rejectsNullRequiredFields() {
        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> market(
                                null, BigInteger.ONE,
                                "Apple", "usdToken", 2, 6, BigInteger.ONE
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> market(
                                "local-dex-001", null,
                                "Apple", "usdToken", 2, 6, BigInteger.ONE
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new Market(
                                "local-dex-001", BigInteger.ONE,
                                null, QUOTE,
                                "Apple", "usdToken", 2, 6,
                                BigInteger.ONE, true
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new Market(
                                "local-dex-001", BigInteger.ONE,
                                BASE, null,
                                "Apple", "usdToken", 2, 6,
                                BigInteger.ONE, true
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> market(
                                "local-dex-001", BigInteger.ONE,
                                null, "usdToken", 2, 6, BigInteger.ONE
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> market(
                                "local-dex-001", BigInteger.ONE,
                                "Apple", null, 2, 6, BigInteger.ONE
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> market(
                                "local-dex-001", BigInteger.ONE,
                                "Apple", "usdToken", 2, 6, null
                        )
                )
        );
    }

    @Test
    void convertsLotsToBaseAtoms() {
        assertEquals(
                BigInteger.valueOf(3000),
                validMarket().baseAmountAtoms(BigInteger.valueOf(3))
        );
    }

    @Test
    void zeroLotsProducesZeroAtoms() {
        assertEquals(
                BigInteger.ZERO,
                validMarket().baseAmountAtoms(BigInteger.ZERO)
        );
    }

    @Test
    void rejectsNullOrNegativeLots() {
        Market market = validMarket();

        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> market.baseAmountAtoms(null)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> market.baseAmountAtoms(BigInteger.valueOf(-1))
                )
        );
    }

    @Test
    void supportsAmountsLargerThanLong() {
        BigInteger huge = new BigInteger("100000000000000000000");

        Market market = market(
                "local-dex-001",
                huge,
                "Apple",
                "usdToken",
                2,
                6,
                huge
        );

        // 10^20 lots × 10^20 atoms per lot = 10^40 atoms
        assertEquals(
                BigInteger.TEN.pow(40),
                market.baseAmountAtoms(huge)
        );
        assertEquals(huge, market.marketId());
    }

    private static Market validMarket() {
        return market(
                "local-dex-001",
                BigInteger.ONE,
                "Apple",
                "usdToken",
                2,
                6,
                BigInteger.valueOf(1000)
        );
    }

    private static Market market(
            String deploymentId,
            BigInteger marketId,
            String baseSymbol,
            String quoteSymbol,
            int baseDecimals,
            int quoteDecimals,
            BigInteger baseLotSizeAtoms
    ) {
        return new Market(
                deploymentId,
                marketId,
                BASE,
                QUOTE,
                baseSymbol,
                quoteSymbol,
                baseDecimals,
                quoteDecimals,
                baseLotSizeAtoms,
                true
        );
    }
}