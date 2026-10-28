package com.dex.backend.indexer.projection;

import com.dex.backend.TestcontainersConfiguration;
import com.dex.backend.blockchain.event.EventMetadata;
import com.dex.backend.blockchain.event.MarketRegisteredEvent;
import com.dex.backend.blockchain.event.OrderCancelledEvent;
import com.dex.backend.blockchain.event.OrderPlacedEvent;
import com.dex.backend.blockchain.event.TradeExecutedEvent;
import com.dex.backend.blockchain.model.ChainPosition;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.order.OrderSide;
import com.dex.backend.order.OrderStatus;
import com.dex.backend.persistence.deployment.ChainDeploymentEntity;
import com.dex.backend.persistence.deployment.ChainDeploymentRepository;
import com.dex.backend.persistence.event.ProcessedEventEntityId;
import com.dex.backend.persistence.event.ProcessedEventRepository;
import com.dex.backend.persistence.market.MarketEntityId;
import com.dex.backend.persistence.market.MarketRepository;
import com.dex.backend.persistence.order.OrderEntity;
import com.dex.backend.persistence.order.OrderEntityId;
import com.dex.backend.persistence.order.OrderRepository;
import com.dex.backend.persistence.trade.TradeEntityId;
import com.dex.backend.persistence.trade.TradeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigInteger;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class EventProjectionServiceIntegrationTest {

    private static final BigInteger MARKET_ID = BigInteger.ONE;
    private static final BigInteger SELL_ORDER_ID = BigInteger.valueOf(11);
    private static final BigInteger BUY_ORDER_ID = BigInteger.valueOf(12);
    private static final Instant START_TIME = Instant.parse("2026-02-01T00:00:00Z");
    private static final EthereumAddress BASE_TOKEN = address('1');
    private static final EthereumAddress QUOTE_TOKEN = address('2');
    private static final EthereumAddress SELLER = address('3');
    private static final EthereumAddress BUYER = address('4');

    @Autowired
    private EventProjectionService projectionService;

    @Autowired
    private ChainDeploymentRepository deploymentRepository;

    @Autowired
    private MarketRepository marketRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void projectsMarketOrdersAndTradeAtomically() {
        ProjectionFixture fixture = fixture("projection-sequence", 'a', 1001);

        assertEquals(EventProjectionResult.APPLIED, projectionService.apply(fixture.marketEvent()));
        assertEquals(EventProjectionResult.APPLIED, projectionService.apply(fixture.sellOrderEvent()));
        assertEquals(EventProjectionResult.APPLIED, projectionService.apply(fixture.buyOrderEvent()));
        assertEquals(EventProjectionResult.APPLIED, projectionService.apply(fixture.tradeEvent()));

        OrderEntity sellOrder = orderRepository.findById(
                new OrderEntityId(fixture.deploymentId(), SELL_ORDER_ID)
        ).orElseThrow();
        OrderEntity buyOrder = orderRepository.findById(
                new OrderEntityId(fixture.deploymentId(), BUY_ORDER_ID)
        ).orElseThrow();
        TradeEntityId tradeId = eventTradeId(fixture.tradeEvent());

        assertEquals(BigInteger.valueOf(60), sellOrder.getRemainingLots());
        assertEquals(OrderStatus.PARTIALLY_FILLED, sellOrder.getStatus());
        assertEquals(BigInteger.ZERO, buyOrder.getRemainingLots());
        assertEquals(OrderStatus.FILLED, buyOrder.getStatus());
        assertTrue(tradeRepository.existsById(tradeId));
        assertEquals(4, processedEventCount(fixture.deploymentId()));
    }

    @Test
    void repeatedEventIsIgnoredWithoutDuplicatingProjectionRows() {
        ProjectionFixture fixture = fixture("projection-duplicate", 'b', 2001);

        assertEquals(EventProjectionResult.APPLIED, projectionService.apply(fixture.marketEvent()));
        assertEquals(
                EventProjectionResult.ALREADY_PROCESSED,
                projectionService.apply(fixture.marketEvent())
        );

        assertTrue(marketRepository.existsById(new MarketEntityId(fixture.deploymentId(), MARKET_ID)));
        assertEquals(1, processedEventCount(fixture.deploymentId()));
        assertEquals(1, marketCount(fixture.deploymentId()));
    }

    @Test
    void cancellationMarksOnlyTheConfirmedOrderRemainderAsCancelled() {
        ProjectionFixture fixture = fixture("projection-cancel", 'c', 3001);
        projectionService.apply(fixture.marketEvent());
        projectionService.apply(fixture.sellOrderEvent());

        OrderCancelledEvent cancellation = new OrderCancelledEvent(
                metadata(fixture, 103, 3),
                SELL_ORDER_ID,
                MARKET_ID,
                BigInteger.valueOf(100)
        );

        assertEquals(EventProjectionResult.APPLIED, projectionService.apply(cancellation));

        OrderEntity order = orderRepository.findById(
                new OrderEntityId(fixture.deploymentId(), SELL_ORDER_ID)
        ).orElseThrow();

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(BigInteger.valueOf(100), order.getRemainingLots());
        assertEquals(3, processedEventCount(fixture.deploymentId()));
    }

    @Test
    void inconsistentTradeRollsBackEveryProjectionChange() {
        ProjectionFixture fixture = fixture("projection-invalid-trade", 'd', 4001);
        projectionService.apply(fixture.marketEvent());
        projectionService.apply(fixture.sellOrderEvent());
        projectionService.apply(fixture.buyOrderEvent());

        TradeExecutedEvent inconsistentTrade = new TradeExecutedEvent(
                metadata(fixture, 104, 4),
                BigInteger.valueOf(501),
                MARKET_ID,
                BUY_ORDER_ID,
                SELL_ORDER_ID,
                BigInteger.valueOf(40),
                BigInteger.TEN,
                BigInteger.ZERO,
                BigInteger.valueOf(61)
        );

        assertThrows(
                EventProjectionException.class,
                () -> projectionService.apply(inconsistentTrade)
        );

        OrderEntity sellOrder = orderRepository.findById(
                new OrderEntityId(fixture.deploymentId(), SELL_ORDER_ID)
        ).orElseThrow();
        OrderEntity buyOrder = orderRepository.findById(
                new OrderEntityId(fixture.deploymentId(), BUY_ORDER_ID)
        ).orElseThrow();
        ProcessedEventEntityId processedEventId = processedEventId(inconsistentTrade.metadata());

        assertEquals(BigInteger.valueOf(100), sellOrder.getRemainingLots());
        assertEquals(OrderStatus.OPEN, sellOrder.getStatus());
        assertEquals(BigInteger.valueOf(40), buyOrder.getRemainingLots());
        assertEquals(OrderStatus.OPEN, buyOrder.getStatus());
        assertFalse(tradeRepository.existsById(eventTradeId(inconsistentTrade)));
        assertFalse(processedEventRepository.existsById(processedEventId));
        assertEquals(3, processedEventCount(fixture.deploymentId()));
    }

    @Test
    void duplicateIdentityWithDifferentBlockMetadataIsRejected() {
        ProjectionFixture fixture = fixture("projection-conflict", 'e', 5001);
        projectionService.apply(fixture.marketEvent());

        EventMetadata original = fixture.marketEvent().metadata();
        MarketRegisteredEvent conflictingEvent = new MarketRegisteredEvent(
                new EventMetadata(
                        original.deploymentId(),
                        original.contractAddress(),
                        original.transactionHash(),
                        hash(999_999),
                        original.position(),
                        original.blockTimestamp()
                ),
                fixture.marketEvent().marketId(),
                fixture.marketEvent().baseTokenAddress(),
                fixture.marketEvent().quoteTokenAddress(),
                fixture.marketEvent().baseSymbol(),
                fixture.marketEvent().quoteSymbol(),
                fixture.marketEvent().baseDecimals(),
                fixture.marketEvent().quoteDecimals(),
                fixture.marketEvent().baseLotSizeAtoms()
        );

        assertThrows(
                EventProjectionException.class,
                () -> projectionService.apply(conflictingEvent)
        );
        assertEquals(1, processedEventCount(fixture.deploymentId()));
        assertEquals(1, marketCount(fixture.deploymentId()));
    }

    private ProjectionFixture fixture(String deploymentId, char seed, long transactionBase) {
        EthereumAddress dexAddress = address(seed);
        deploymentRepository.saveAndFlush(new ChainDeploymentEntity(
                deploymentId,
                BlockchainMode.MOCK,
                BigInteger.valueOf(transactionBase),
                dexAddress,
                BigInteger.valueOf(100),
                hash(transactionBase)
        ));

        ProjectionFixture baseFixture = new ProjectionFixture(
                deploymentId,
                dexAddress,
                transactionBase,
                null,
                null,
                null,
                null
        );
        MarketRegisteredEvent marketEvent = new MarketRegisteredEvent(
                metadata(baseFixture, 100, 0),
                MARKET_ID,
                BASE_TOKEN,
                QUOTE_TOKEN,
                "BASE",
                "QUOTE",
                18,
                6,
                new BigInteger("1000000000000000000")
        );
        OrderPlacedEvent sellOrderEvent = new OrderPlacedEvent(
                metadata(baseFixture, 101, 1),
                SELL_ORDER_ID,
                MARKET_ID,
                SELLER,
                OrderSide.SELL,
                BigInteger.TEN,
                BigInteger.valueOf(100)
        );
        OrderPlacedEvent buyOrderEvent = new OrderPlacedEvent(
                metadata(baseFixture, 102, 2),
                BUY_ORDER_ID,
                MARKET_ID,
                BUYER,
                OrderSide.BUY,
                BigInteger.valueOf(12),
                BigInteger.valueOf(40)
        );
        TradeExecutedEvent tradeEvent = new TradeExecutedEvent(
                metadata(baseFixture, 103, 3),
                BigInteger.valueOf(500),
                MARKET_ID,
                BUY_ORDER_ID,
                SELL_ORDER_ID,
                BigInteger.valueOf(40),
                BigInteger.TEN,
                BigInteger.ZERO,
                BigInteger.valueOf(60)
        );

        return new ProjectionFixture(
                deploymentId,
                dexAddress,
                transactionBase,
                marketEvent,
                sellOrderEvent,
                buyOrderEvent,
                tradeEvent
        );
    }

    private static EventMetadata metadata(ProjectionFixture fixture, long blockNumber, long eventOffset) {
        return new EventMetadata(
                fixture.deploymentId(),
                fixture.dexAddress(),
                hash(fixture.transactionBase() + eventOffset),
                hash(100_000 + blockNumber + fixture.transactionBase()),
                new ChainPosition(
                        BigInteger.valueOf(blockNumber),
                        BigInteger.ZERO,
                        BigInteger.ZERO
                ),
                START_TIME.plusSeconds(blockNumber)
        );
    }

    private int processedEventCount(String deploymentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE deployment_id = ?",
                Integer.class,
                deploymentId
        );
        return count == null ? 0 : count;
    }

    private int marketCount(String deploymentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM markets WHERE deployment_id = ?",
                Integer.class,
                deploymentId
        );
        return count == null ? 0 : count;
    }

    private static TradeEntityId eventTradeId(TradeExecutedEvent event) {
        return new TradeEntityId(
                event.metadata().deploymentId(),
                event.metadata().transactionHash(),
                event.metadata().position().logIndex()
        );
    }

    private static ProcessedEventEntityId processedEventId(EventMetadata metadata) {
        return new ProcessedEventEntityId(
                metadata.deploymentId(),
                metadata.transactionHash(),
                metadata.position().logIndex()
        );
    }

    private static EthereumAddress address(char hexadecimalCharacter) {
        return new EthereumAddress("0x" + String.valueOf(hexadecimalCharacter).repeat(40));
    }

    private static Hash256 hash(long value) {
        return new Hash256("0x" + "%064x".formatted(value));
    }

    private record ProjectionFixture(
            String deploymentId,
            EthereumAddress dexAddress,
            long transactionBase,
            MarketRegisteredEvent marketEvent,
            OrderPlacedEvent sellOrderEvent,
            OrderPlacedEvent buyOrderEvent,
            TradeExecutedEvent tradeEvent
    ) {
    }
}
