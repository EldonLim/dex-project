package com.dex.backend.blockchain.mock;

import com.dex.backend.blockchain.event.DexEvent;
import com.dex.backend.blockchain.event.EventMetadata;
import com.dex.backend.blockchain.event.MarketRegisteredEvent;
import com.dex.backend.blockchain.event.OrderPlacedEvent;
import com.dex.backend.blockchain.model.BlockReference;
import com.dex.backend.blockchain.model.ChainIdentity;
import com.dex.backend.blockchain.model.ChainPosition;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.blockchain.model.MarketSnapshot;
import com.dex.backend.blockchain.model.OrderSnapshot;
import com.dex.backend.market.Market;
import com.dex.backend.order.Order;
import com.dex.backend.order.OrderSide;
import com.dex.backend.order.OrderStatus;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class MockBlockchainFixtures {

    public static final String DEPLOYMENT_ID = "mock-dex-001";

    public static final BigInteger CHAIN_ID = BigInteger.valueOf(31337);
    public static final BigInteger DEPLOYMENT_BLOCK = BigInteger.valueOf(100);
    public static final BigInteger LATEST_INITIAL_BLOCK = BigInteger.valueOf(103);

    public static final Instant START_TIMESTAMP =
            Instant.parse("2026-01-01T00:00:00Z");

    public static final long BLOCK_INTERVAL_SECONDS = 12;

    public static final EthereumAddress DEX_ADDRESS =
            new EthereumAddress("0x" + "d".repeat(40));

    public static final EthereumAddress BASE_TOKEN_ADDRESS =
            new EthereumAddress("0x" + "a".repeat(40));

    public static final EthereumAddress QUOTE_TOKEN_ADDRESS =
            new EthereumAddress("0x" + "b".repeat(40));

    public static final EthereumAddress ALICE_ADDRESS =
            new EthereumAddress("0x" + "1".repeat(40));

    public static final EthereumAddress BOB_ADDRESS =
            new EthereumAddress("0x" + "2".repeat(40));

    public static final EthereumAddress EXECUTOR_ADDRESS =
            new EthereumAddress("0x" + "e".repeat(40));

    public static final BigInteger MARKET_ID = BigInteger.ONE;
    public static final String BASE_SYMBOL = "APPLE";
    public static final String QUOTE_SYMBOL = "USDToken";
    public static final int BASE_DECIMALS = 2;
    public static final int QUOTE_DECIMALS = 2;
    public static final BigInteger BASE_LOT_SIZE_ATOMS =
            BigInteger.valueOf(1000);

    public static final BigInteger ALICE_ORDER_ID = BigInteger.ONE;
    public static final BigInteger BOB_ORDER_ID = BigInteger.TWO;

    public static final BigInteger ALICE_LIMIT_PRICE = BigInteger.TEN;
    public static final BigInteger ALICE_LOTS = BigInteger.valueOf(100);

    public static final BigInteger BOB_LIMIT_PRICE = BigInteger.valueOf(12);
    public static final BigInteger BOB_LOTS = BigInteger.valueOf(40);

    public static final BlockReference BLOCK_100 = fixtureBlock(100);
    public static final BlockReference BLOCK_101 = fixtureBlock(101);
    public static final BlockReference BLOCK_102 = fixtureBlock(102);
    public static final BlockReference BLOCK_103 = fixtureBlock(103);

    public static final List<BlockReference> INITIAL_BLOCKS = List.of(
            BLOCK_100,
            BLOCK_101,
            BLOCK_102,
            BLOCK_103
    );

    public static final ChainIdentity CHAIN_IDENTITY = new ChainIdentity(
            DEPLOYMENT_ID,
            CHAIN_ID,
            DEX_ADDRESS,
            DEPLOYMENT_BLOCK,
            BLOCK_100.hash()
    );

    // Fixed synthetic transaction hashes, distinct from fixture block hashes.
    public static final Hash256 MARKET_REGISTRATION_TX_HASH =
            hash(BigInteger.valueOf(1001));

    public static final Hash256 ALICE_PLACEMENT_TX_HASH =
            hash(BigInteger.valueOf(1002));

    public static final Hash256 BOB_PLACEMENT_TX_HASH =
            hash(BigInteger.valueOf(1003));

    public static final MarketRegisteredEvent MARKET_REGISTERED_EVENT =
            new MarketRegisteredEvent(
                    metadata(BLOCK_101, MARKET_REGISTRATION_TX_HASH),
                    MARKET_ID,
                    BASE_TOKEN_ADDRESS,
                    QUOTE_TOKEN_ADDRESS,
                    BASE_SYMBOL,
                    QUOTE_SYMBOL,
                    BASE_DECIMALS,
                    QUOTE_DECIMALS,
                    BASE_LOT_SIZE_ATOMS
            );

    public static final OrderPlacedEvent ALICE_ORDER_PLACED_EVENT =
            new OrderPlacedEvent(
                    metadata(BLOCK_102, ALICE_PLACEMENT_TX_HASH),
                    ALICE_ORDER_ID,
                    MARKET_ID,
                    ALICE_ADDRESS,
                    OrderSide.SELL,
                    ALICE_LIMIT_PRICE,
                    ALICE_LOTS
            );

    public static final OrderPlacedEvent BOB_ORDER_PLACED_EVENT =
            new OrderPlacedEvent(
                    metadata(BLOCK_103, BOB_PLACEMENT_TX_HASH),
                    BOB_ORDER_ID,
                    MARKET_ID,
                    BOB_ADDRESS,
                    OrderSide.BUY,
                    BOB_LIMIT_PRICE,
                    BOB_LOTS
            );

    public static final List<DexEvent> INITIAL_EVENTS = List.of(
            MARKET_REGISTERED_EVENT,
            ALICE_ORDER_PLACED_EVENT,
            BOB_ORDER_PLACED_EVENT
    );

    public static final Market MARKET = new Market(
            DEPLOYMENT_ID,
            MARKET_ID,
            BASE_TOKEN_ADDRESS,
            QUOTE_TOKEN_ADDRESS,
            BASE_SYMBOL,
            QUOTE_SYMBOL,
            BASE_DECIMALS,
            QUOTE_DECIMALS,
            BASE_LOT_SIZE_ATOMS,
            true
    );

    public static final MarketSnapshot MARKET_SNAPSHOT = new MarketSnapshot(
            DEPLOYMENT_ID,
            MARKET_ID,
            BASE_TOKEN_ADDRESS,
            QUOTE_TOKEN_ADDRESS,
            BASE_LOT_SIZE_ATOMS,
            true
    );

    public static final Order ALICE_SELL_ORDER =
            initialOrder(ALICE_ORDER_PLACED_EVENT);

    public static final Order BOB_BUY_ORDER =
            initialOrder(BOB_ORDER_PLACED_EVENT);

    public static final List<Order> INITIAL_ORDERS = List.of(
            ALICE_SELL_ORDER,
            BOB_BUY_ORDER
    );

    public static final OrderSnapshot ALICE_SELL_ORDER_SNAPSHOT =
            snapshot(ALICE_SELL_ORDER);

    public static final OrderSnapshot BOB_BUY_ORDER_SNAPSHOT =
            snapshot(BOB_BUY_ORDER);

    public static final List<OrderSnapshot> INITIAL_ORDER_SNAPSHOTS = List.of(
            ALICE_SELL_ORDER_SNAPSHOT,
            BOB_BUY_ORDER_SNAPSHOT
    );

    private MockBlockchainFixtures() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * Produces a deterministic synthetic 256-bit hash representation.
     *
     * <p>This formats a number; it does not perform cryptographic hashing.
     *
     * @param value a non-negative integer fitting within 256 bits
     * @return 0x followed by exactly 64 lowercase hexadecimal characters
     */
    public static Hash256 hash(BigInteger value) {
        Objects.requireNonNull(value, "value must not be null");

        if (value.signum() < 0) {
            throw new IllegalArgumentException("value must be non-negative");
        }

        if (value.bitLength() > 256) {
            throw new IllegalArgumentException(
                    "value must fit within 64 hexadecimal characters"
            );
        }

        String hex = value.toString(16);
        String padded = "0".repeat(64 - hex.length()) + hex;

        return new Hash256("0x" + padded);
    }

    private static BlockReference fixtureBlock(long blockNumber) {
        BigInteger number = BigInteger.valueOf(blockNumber);

        long offsetSeconds = number.subtract(DEPLOYMENT_BLOCK)
                .multiply(BigInteger.valueOf(BLOCK_INTERVAL_SECONDS))
                .longValueExact();

        return new BlockReference(
                number,
                hash(number),
                START_TIMESTAMP.plusSeconds(offsetSeconds)
        );
    }

    private static EventMetadata metadata(
            BlockReference block,
            Hash256 transactionHash
    ) {
        return new EventMetadata(
                DEPLOYMENT_ID,
                DEX_ADDRESS,
                transactionHash,
                block.hash(),
                new ChainPosition(
                        block.number(),
                        BigInteger.ZERO,
                        BigInteger.ZERO
                ),
                block.timestamp()
        );
    }

    private static Order initialOrder(OrderPlacedEvent event) {
        EventMetadata metadata = event.metadata();

        return new Order(
                metadata.deploymentId(),
                event.orderId(),
                event.marketId(),
                event.owner(),
                event.side(),
                event.limitPriceQuoteAtomsPerLot(),
                event.originalLots(),
                event.originalLots(),
                OrderStatus.OPEN,
                metadata.position(),
                metadata.transactionHash(),
                metadata.blockHash(),
                metadata.blockTimestamp()
        );
    }

    private static OrderSnapshot snapshot(Order order) {
        return new OrderSnapshot(
                order.deploymentId(),
                order.orderId(),
                order.marketId(),
                order.owner(),
                order.side(),
                order.limitPriceQuoteAtomsPerLot(),
                order.originalLots(),
                order.remainingLots(),
                order.status()
        );
    }
}