package com.dex.backend.matcher;

import com.dex.backend.TestcontainersConfiguration;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.order.OrderSide;
import com.dex.backend.order.OrderStatus;
import com.dex.backend.persistence.deployment.ChainDeploymentEntity;
import com.dex.backend.persistence.deployment.ChainDeploymentRepository;
import com.dex.backend.persistence.market.MarketEntity;
import com.dex.backend.persistence.market.MarketEntityId;
import com.dex.backend.persistence.market.MarketRepository;
import com.dex.backend.persistence.order.OrderEntity;
import com.dex.backend.persistence.order.OrderEntityId;
import com.dex.backend.persistence.order.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "dex.blockchain.deployment-id=projection-match-test")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ProjectionMatchServiceIntegrationTest {

    private static final String DEPLOYMENT_ID = "projection-match-test";
    private static final String OTHER_DEPLOYMENT_ID = "other-deployment";

    private final ProjectionMatchService matchService;
    private final OrderRepository orderRepository;
    private final MarketRepository marketRepository;
    private final ChainDeploymentRepository deploymentRepository;

    @Autowired
    ProjectionMatchServiceIntegrationTest(
            ProjectionMatchService matchService,
            OrderRepository orderRepository,
            MarketRepository marketRepository,
            ChainDeploymentRepository deploymentRepository
    ) {
        this.matchService = matchService;
        this.orderRepository = orderRepository;
        this.marketRepository = marketRepository;
        this.deploymentRepository = deploymentRepository;
    }

    @BeforeEach
    void clearProjection() {
        clearProjectionData();
    }

    @AfterEach
    void clearProjectionAfterTest() {
        clearProjectionData();
    }

    @Test
    void returnsEmptyWithoutMarketsOrWithoutACrossedPair() {
        assertTrue(matchService.findNextProposal().isEmpty());

        saveDeployment(DEPLOYMENT_ID, 1);
        saveMarket(DEPLOYMENT_ID, BigInteger.ONE, true);
        saveOpenOrder(DEPLOYMENT_ID, 1, 1, OrderSide.BUY, 1, 100, 10, 1);
        saveOpenOrder(DEPLOYMENT_ID, 1, 2, OrderSide.SELL, 2, 101, 10, 2);
        orderRepository.flush();

        assertTrue(matchService.findNextProposal().isEmpty());
    }

    @Test
    void ignoresInactiveAndOtherDeploymentMarkets() {
        saveDeployment(DEPLOYMENT_ID, 1);
        saveDeployment(OTHER_DEPLOYMENT_ID, 2);

        saveMarket(OTHER_DEPLOYMENT_ID, BigInteger.ZERO, true);
        saveMatchingPair(OTHER_DEPLOYMENT_ID, BigInteger.ZERO, 1, 2, 1);

        saveMarket(DEPLOYMENT_ID, BigInteger.ONE, false);
        saveMatchingPair(DEPLOYMENT_ID, BigInteger.ONE, 3, 4, 10);

        saveMarket(DEPLOYMENT_ID, BigInteger.TWO, true);
        saveMatchingPair(DEPLOYMENT_ID, BigInteger.TWO, 5, 6, 20);
        orderRepository.flush();

        MatchProposal result = matchService.findNextProposal().orElseThrow();

        assertAll(
                () -> assertEquals(DEPLOYMENT_ID, result.deploymentId()),
                () -> assertEquals(BigInteger.TWO, result.marketId()),
                () -> assertEquals(BigInteger.valueOf(5), result.buyOrderId()),
                () -> assertEquals(BigInteger.valueOf(6), result.sellOrderId())
        );
    }

    @Test
    void selectsLowestNumericMarketIdRegardlessOfInsertionOrder() {
        saveDeployment(DEPLOYMENT_ID, 1);

        saveMarket(DEPLOYMENT_ID, BigInteger.TEN, true);
        saveMatchingPair(DEPLOYMENT_ID, BigInteger.TEN, 10, 11, 100);
        saveMarket(DEPLOYMENT_ID, BigInteger.TWO, true);
        saveMatchingPair(DEPLOYMENT_ID, BigInteger.TWO, 20, 21, 200);
        orderRepository.flush();

        MatchProposal result = matchService.findNextProposal().orElseThrow();

        assertAll(
                () -> assertEquals(BigInteger.TWO, result.marketId()),
                () -> assertEquals(BigInteger.valueOf(20), result.buyOrderId()),
                () -> assertEquals(BigInteger.valueOf(21), result.sellOrderId())
        );
    }

    @Test
    void continuesAfterLowerMarketHasNoMatch() {
        saveDeployment(DEPLOYMENT_ID, 1);
        saveMarket(DEPLOYMENT_ID, BigInteger.ONE, true);
        saveOpenOrder(DEPLOYMENT_ID, 1, 1, OrderSide.BUY, 1, 100, 10, 1);
        saveOpenOrder(DEPLOYMENT_ID, 1, 2, OrderSide.SELL, 2, 101, 10, 2);

        saveMarket(DEPLOYMENT_ID, BigInteger.TWO, true);
        saveMatchingPair(DEPLOYMENT_ID, BigInteger.TWO, 3, 4, 10);
        orderRepository.flush();

        assertEquals(
                BigInteger.TWO,
                matchService.findNextProposal().orElseThrow().marketId()
        );
    }

    @Test
    void mapsPartialQuantitiesAndUsesOlderOrderExecutionPrice() {
        saveDeployment(DEPLOYMENT_ID, 1);
        saveMarket(DEPLOYMENT_ID, BigInteger.ONE, true);
        saveOrder(
                DEPLOYMENT_ID,
                BigInteger.ONE,
                BigInteger.ONE,
                OrderSide.SELL,
                1,
                BigInteger.valueOf(100),
                BigInteger.valueOf(50),
                BigInteger.valueOf(20),
                OrderStatus.PARTIALLY_FILLED,
                1,
                0,
                0,
                1
        );
        saveOrder(
                DEPLOYMENT_ID,
                BigInteger.ONE,
                BigInteger.TWO,
                OrderSide.BUY,
                2,
                BigInteger.valueOf(110),
                BigInteger.valueOf(100),
                BigInteger.valueOf(70),
                OrderStatus.PARTIALLY_FILLED,
                2,
                0,
                0,
                2
        );
        orderRepository.flush();

        MatchProposal result = matchService.findNextProposal().orElseThrow();

        assertAll(
                () -> assertEquals(BigInteger.valueOf(20), result.fillLots()),
                () -> assertEquals(
                        BigInteger.valueOf(100),
                        result.executionPriceQuoteAtomsPerLot()
                )
        );
    }

    @Test
    void repositoryEligibilityExcludesFilledAndCancelledOrders() {
        saveDeployment(DEPLOYMENT_ID, 1);
        saveMarket(DEPLOYMENT_ID, BigInteger.ONE, true);
        saveOrder(
                DEPLOYMENT_ID,
                BigInteger.ONE,
                BigInteger.ONE,
                OrderSide.BUY,
                1,
                BigInteger.valueOf(1_000),
                BigInteger.TEN,
                BigInteger.ZERO,
                OrderStatus.FILLED,
                1,
                0,
                0,
                1
        );
        saveOrder(
                DEPLOYMENT_ID,
                BigInteger.ONE,
                BigInteger.TWO,
                OrderSide.SELL,
                2,
                BigInteger.ONE,
                BigInteger.TEN,
                BigInteger.TEN,
                OrderStatus.CANCELLED,
                2,
                0,
                0,
                2
        );
        saveOpenOrder(DEPLOYMENT_ID, 1, 3, OrderSide.BUY, 3, 110, 10, 3);
        saveOpenOrder(DEPLOYMENT_ID, 1, 4, OrderSide.SELL, 4, 100, 10, 4);
        orderRepository.flush();

        MatchProposal result = matchService.findNextProposal().orElseThrow();

        assertAll(
                () -> assertEquals(BigInteger.valueOf(3), result.buyOrderId()),
                () -> assertEquals(BigInteger.valueOf(4), result.sellOrderId())
        );
    }

    @Test
    void preservesPriceTimePriorityThroughEntityMapping() {
        saveDeployment(DEPLOYMENT_ID, 1);
        saveMarket(DEPLOYMENT_ID, BigInteger.ONE, true);
        saveOpenOrder(DEPLOYMENT_ID, 1, 30, OrderSide.BUY, 1, 110, 10, 2);
        saveOpenOrder(DEPLOYMENT_ID, 1, 20, OrderSide.BUY, 2, 110, 10, 1);
        saveOpenOrder(DEPLOYMENT_ID, 1, 10, OrderSide.BUY, 3, 110, 10, 1);
        saveOpenOrder(DEPLOYMENT_ID, 1, 50, OrderSide.SELL, 4, 100, 10, 4);
        saveOpenOrder(DEPLOYMENT_ID, 1, 40, OrderSide.SELL, 5, 100, 10, 3);
        orderRepository.flush();

        MatchProposal result = matchService.findNextProposal().orElseThrow();

        assertAll(
                () -> assertEquals(BigInteger.TEN, result.buyOrderId()),
                () -> assertEquals(BigInteger.valueOf(40), result.sellOrderId())
        );
    }

    @Test
    void loadsDeepEnoughToFindDifferentOwnerAfterSelfTrade() {
        saveDeployment(DEPLOYMENT_ID, 1);
        saveMarket(DEPLOYMENT_ID, BigInteger.ONE, true);
        saveOpenOrder(DEPLOYMENT_ID, 1, 1, OrderSide.BUY, 1, 110, 10, 1);
        saveOpenOrder(DEPLOYMENT_ID, 1, 2, OrderSide.SELL, 1, 90, 10, 2);

        for (long orderId = 3; orderId <= 250; orderId++) {
            saveOpenOrder(
                    DEPLOYMENT_ID,
                    1,
                    orderId,
                    OrderSide.SELL,
                    1,
                    90,
                    10,
                    orderId
            );
        }

        saveOpenOrder(DEPLOYMENT_ID, 1, 251, OrderSide.SELL, 2, 100, 10, 251);
        orderRepository.flush();

        MatchProposal result = matchService.findNextProposal().orElseThrow();

        assertAll(
                () -> assertEquals(BigInteger.ONE, result.buyOrderId()),
                () -> assertEquals(BigInteger.valueOf(251), result.sellOrderId())
        );
    }

    @Test
    void preservesLargeIntegerPrecisionAndDoesNotModifyProjectionRows() {
        BigInteger marketId = new BigInteger("90071992547409930000000001");
        BigInteger buyId = new BigInteger("90071992547409930000000002");
        BigInteger sellId = new BigInteger("90071992547409930000000003");
        BigInteger buyPrice = new BigInteger("90071992547409930000000100");
        BigInteger sellPrice = new BigInteger("90071992547409930000000099");
        BigInteger buyRemaining = new BigInteger("90071992547409930000000050");
        BigInteger sellRemaining = new BigInteger("90071992547409930000000040");

        saveDeployment(DEPLOYMENT_ID, 1);
        saveMarket(DEPLOYMENT_ID, marketId, true);
        saveOrder(
                DEPLOYMENT_ID,
                marketId,
                buyId,
                OrderSide.BUY,
                1,
                buyPrice,
                buyRemaining,
                buyRemaining,
                OrderStatus.OPEN,
                1,
                0,
                0,
                1
        );
        saveOrder(
                DEPLOYMENT_ID,
                marketId,
                sellId,
                OrderSide.SELL,
                2,
                sellPrice,
                sellRemaining,
                sellRemaining,
                OrderStatus.OPEN,
                2,
                0,
                0,
                2
        );
        orderRepository.flush();

        MatchProposal first = matchService.findNextProposal().orElseThrow();
        MatchProposal second = matchService.findNextProposal().orElseThrow();
        OrderEntity storedBuy = orderRepository.findById(
                new OrderEntityId(DEPLOYMENT_ID, buyId)
        ).orElseThrow();
        OrderEntity storedSell = orderRepository.findById(
                new OrderEntityId(DEPLOYMENT_ID, sellId)
        ).orElseThrow();

        assertAll(
                () -> assertEquals(first, second),
                () -> assertEquals(buyId, first.buyOrderId()),
                () -> assertEquals(sellId, first.sellOrderId()),
                () -> assertEquals(sellRemaining, first.fillLots()),
                () -> assertEquals(buyPrice, first.executionPriceQuoteAtomsPerLot()),
                () -> assertEquals(buyRemaining, storedBuy.getRemainingLots()),
                () -> assertEquals(OrderStatus.OPEN, storedBuy.getStatus()),
                () -> assertEquals(sellRemaining, storedSell.getRemainingLots()),
                () -> assertEquals(OrderStatus.OPEN, storedSell.getStatus()),
                () -> assertEquals(2, orderRepository.count())
        );
    }

    private void clearProjectionData() {
        orderRepository.deleteAll();
        marketRepository.deleteAll();
        deploymentRepository.deleteAll();
    }

    private void saveDeployment(String deploymentId, long seed) {
        deploymentRepository.saveAndFlush(new ChainDeploymentEntity(
                deploymentId,
                BlockchainMode.MOCK,
                BigInteger.valueOf(seed),
                address(10_000 + seed),
                address(10_000 + seed),
                BigInteger.ZERO,
                hash(20_000 + seed)
        ));
    }

    private void saveMarket(String deploymentId, BigInteger marketId, boolean active) {
        marketRepository.saveAndFlush(new MarketEntity(
                new MarketEntityId(deploymentId, marketId),
                address(100),
                address(101),
                "TKA",
                "TKB",
                18,
                18,
                BigInteger.valueOf(1_000_000),
                active
        ));
    }

    private void saveMatchingPair(
            String deploymentId,
            BigInteger marketId,
            long buyOrderId,
            long sellOrderId,
            long seed
    ) {
        saveOpenOrder(
                deploymentId,
                marketId.longValueExact(),
                buyOrderId,
                OrderSide.BUY,
                seed + 1,
                110,
                10,
                seed
        );
        saveOpenOrder(
                deploymentId,
                marketId.longValueExact(),
                sellOrderId,
                OrderSide.SELL,
                seed + 2,
                100,
                10,
                seed + 1
        );
    }

    private void saveOpenOrder(
            String deploymentId,
            long marketId,
            long orderId,
            OrderSide side,
            long ownerSeed,
            long price,
            long remaining,
            long block
    ) {
        saveOrder(
                deploymentId,
                BigInteger.valueOf(marketId),
                BigInteger.valueOf(orderId),
                side,
                ownerSeed,
                BigInteger.valueOf(price),
                BigInteger.valueOf(remaining),
                BigInteger.valueOf(remaining),
                OrderStatus.OPEN,
                block,
                0,
                0,
                orderId
        );
    }

    private void saveOrder(
            String deploymentId,
            BigInteger marketId,
            BigInteger orderId,
            OrderSide side,
            long ownerSeed,
            BigInteger price,
            BigInteger originalLots,
            BigInteger remainingLots,
            OrderStatus status,
            long block,
            long transaction,
            long log,
            long hashSeed
    ) {
        orderRepository.save(new OrderEntity(
                new OrderEntityId(deploymentId, orderId),
                marketId,
                address(ownerSeed),
                side,
                price,
                originalLots,
                remainingLots,
                status,
                BigInteger.valueOf(block),
                BigInteger.valueOf(transaction),
                BigInteger.valueOf(log),
                hash(30_000 + hashSeed),
                hash(40_000 + hashSeed),
                Instant.parse("2026-01-01T00:00:00Z").plusSeconds(block)
        ));
    }

    private static EthereumAddress address(long value) {
        return new EthereumAddress("0x" + String.format("%040x", value));
    }

    private static Hash256 hash(long value) {
        return new Hash256("0x" + String.format("%064x", value));
    }
}
