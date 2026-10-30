package com.dex.backend.matcher;

import com.dex.backend.TestcontainersConfiguration;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.config.DexProperties;
import com.dex.backend.order.OrderSide;
import com.dex.backend.order.OrderStatus;
import com.dex.backend.persistence.deployment.ChainDeploymentEntity;
import com.dex.backend.persistence.deployment.ChainDeploymentRepository;
import com.dex.backend.persistence.execution.ExecutionAttemptRepository;
import com.dex.backend.persistence.execution.ExecutionAttemptStatus;
import com.dex.backend.persistence.execution.ReservedOrderPair;
import com.dex.backend.persistence.market.MarketEntity;
import com.dex.backend.persistence.market.MarketEntityId;
import com.dex.backend.persistence.market.MarketRepository;
import com.dex.backend.persistence.order.OrderEntity;
import com.dex.backend.persistence.order.OrderEntityId;
import com.dex.backend.persistence.order.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "dex.blockchain.deployment-id=projection-exclusion-test",
        "dex.matcher.ineligibility-duration=30s"
})
@ActiveProfiles("test")
@Import({
        TestcontainersConfiguration.class,
        ProjectionMatchExclusionIntegrationTest.ClockConfiguration.class
})
class ProjectionMatchExclusionIntegrationTest {

    private static final String DEPLOYMENT_ID = "projection-exclusion-test";
    private static final String OTHER_DEPLOYMENT_ID = "other-exclusion-test";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private final ProjectionMatchService matchService;
    private final MatchReservationService reservationService;
    private final TemporaryOrderIneligibilityRegistry ineligibilityRegistry;
    private final MutableClock clock;
    private final ExecutionAttemptRepository executionAttemptRepository;
    private final OrderRepository orderRepository;
    private final MarketRepository marketRepository;
    private final ChainDeploymentRepository deploymentRepository;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    ProjectionMatchExclusionIntegrationTest(
            ProjectionMatchService matchService,
            MatchReservationService reservationService,
            TemporaryOrderIneligibilityRegistry ineligibilityRegistry,
            MutableClock clock,
            ExecutionAttemptRepository executionAttemptRepository,
            OrderRepository orderRepository,
            MarketRepository marketRepository,
            ChainDeploymentRepository deploymentRepository,
            JdbcTemplate jdbcTemplate
    ) {
        this.matchService = matchService;
        this.reservationService = reservationService;
        this.ineligibilityRegistry = ineligibilityRegistry;
        this.clock = clock;
        this.executionAttemptRepository = executionAttemptRepository;
        this.orderRepository = orderRepository;
        this.marketRepository = marketRepository;
        this.deploymentRepository = deploymentRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void resetState() {
        clearData();
        clock.reset();
    }

    @AfterEach
    void clearStateAfterTest() {
        clearData();
        clock.reset();
    }

    @ParameterizedTest
    @EnumSource(
            value = ExecutionAttemptStatus.class,
            names = {"RESERVED", "SIGNED", "SUBMITTED", "UNCERTAIN"}
    )
    void unresolvedAttemptsReserveBothOrders(ExecutionAttemptStatus status) {
        saveDeployment(DEPLOYMENT_ID, 1);
        saveMarket(DEPLOYMENT_ID, BigInteger.ONE);
        saveOpenOrder(DEPLOYMENT_ID, 1, 1, OrderSide.BUY, 1, 110, 10, 1);
        saveOpenOrder(DEPLOYMENT_ID, 1, 2, OrderSide.SELL, 2, 90, 10, 2);
        saveOpenOrder(DEPLOYMENT_ID, 1, 3, OrderSide.BUY, 3, 105, 10, 3);
        saveOpenOrder(DEPLOYMENT_ID, 1, 4, OrderSide.SELL, 4, 100, 10, 4);
        orderRepository.flush();
        insertAttempt(DEPLOYMENT_ID, BigInteger.ONE, 1, 2, status);

        MatchProposal result = matchService.findNextProposal().orElseThrow();

        assertAll(
                () -> assertEquals(BigInteger.valueOf(3), result.buyOrderId()),
                () -> assertEquals(BigInteger.valueOf(4), result.sellOrderId()),
                () -> assertEquals(4, orderRepository.count()),
                () -> assertEquals(1, executionAttemptRepository.count())
        );
    }

    @ParameterizedTest
    @EnumSource(
            value = ExecutionAttemptStatus.class,
            names = {"CONFIRMED", "REVERTED", "ABANDONED"}
    )
    void resolvedAttemptsDoNotReserveOrders(ExecutionAttemptStatus status) {
        saveDeployment(DEPLOYMENT_ID, 1);
        saveMarket(DEPLOYMENT_ID, BigInteger.ONE);
        saveOpenOrder(DEPLOYMENT_ID, 1, 1, OrderSide.BUY, 1, 110, 10, 1);
        saveOpenOrder(DEPLOYMENT_ID, 1, 2, OrderSide.SELL, 2, 90, 10, 2);
        orderRepository.flush();
        insertAttempt(DEPLOYMENT_ID, BigInteger.ONE, 1, 2, status);

        MatchProposal result = matchService.findNextProposal().orElseThrow();

        assertAll(
                () -> assertEquals(BigInteger.ONE, result.buyOrderId()),
                () -> assertEquals(BigInteger.TWO, result.sellOrderId())
        );
    }

    @Test
    void otherDeploymentReservationsDoNotAffectDiscovery() {
        saveDeployment(DEPLOYMENT_ID, 1);
        saveMarket(DEPLOYMENT_ID, BigInteger.ONE);
        saveOpenOrder(DEPLOYMENT_ID, 1, 1, OrderSide.BUY, 1, 110, 10, 1);
        saveOpenOrder(DEPLOYMENT_ID, 1, 2, OrderSide.SELL, 2, 90, 10, 2);

        saveDeployment(OTHER_DEPLOYMENT_ID, 2);
        saveMarket(OTHER_DEPLOYMENT_ID, BigInteger.ONE);
        saveOpenOrder(OTHER_DEPLOYMENT_ID, 1, 1, OrderSide.BUY, 3, 110, 10, 1);
        saveOpenOrder(OTHER_DEPLOYMENT_ID, 1, 2, OrderSide.SELL, 4, 90, 10, 2);
        orderRepository.flush();
        insertAttempt(
                OTHER_DEPLOYMENT_ID,
                BigInteger.ONE,
                1,
                2,
                ExecutionAttemptStatus.RESERVED
        );

        MatchProposal result = matchService.findNextProposal().orElseThrow();

        assertEquals(DEPLOYMENT_ID, result.deploymentId());
        assertEquals(BigInteger.ONE, result.buyOrderId());
        assertEquals(BigInteger.TWO, result.sellOrderId());
    }

    @Test
    void temporaryExclusionFindsADeeperOrderAndExpiryRestoresPriority() {
        saveDeployment(DEPLOYMENT_ID, 1);
        saveMarket(DEPLOYMENT_ID, BigInteger.ONE);
        saveOpenOrder(DEPLOYMENT_ID, 1, 1, OrderSide.BUY, 1, 110, 10, 1);
        saveOpenOrder(DEPLOYMENT_ID, 1, 2, OrderSide.SELL, 2, 90, 10, 2);
        saveOpenOrder(DEPLOYMENT_ID, 1, 3, OrderSide.BUY, 3, 105, 10, 3);
        orderRepository.flush();

        ineligibilityRegistry.markTemporarilyIneligible(
                DEPLOYMENT_ID,
                BigInteger.ONE
        );

        MatchProposal duringCooldown = matchService.findNextProposal().orElseThrow();
        assertEquals(BigInteger.valueOf(3), duringCooldown.buyOrderId());
        assertEquals(BigInteger.TWO, duringCooldown.sellOrderId());

        clock.advance(Duration.ofSeconds(30));
        MatchProposal afterCooldown = matchService.findNextProposal().orElseThrow();
        assertEquals(BigInteger.ONE, afterCooldown.buyOrderId());
        assertEquals(BigInteger.TWO, afterCooldown.sellOrderId());
    }

    @Test
    void durableAndTemporaryExclusionsCombineAcrossMarkets() {
        saveDeployment(DEPLOYMENT_ID, 1);
        saveMarket(DEPLOYMENT_ID, BigInteger.ONE);
        saveOpenOrder(DEPLOYMENT_ID, 1, 1, OrderSide.BUY, 1, 110, 10, 1);
        saveOpenOrder(DEPLOYMENT_ID, 1, 2, OrderSide.SELL, 2, 90, 10, 2);

        saveMarket(DEPLOYMENT_ID, BigInteger.TWO);
        saveOpenOrder(DEPLOYMENT_ID, 2, 3, OrderSide.BUY, 3, 110, 10, 3);
        saveOpenOrder(DEPLOYMENT_ID, 2, 4, OrderSide.SELL, 4, 90, 10, 4);
        saveOpenOrder(DEPLOYMENT_ID, 2, 5, OrderSide.BUY, 5, 105, 10, 5);
        orderRepository.flush();

        insertAttempt(
                DEPLOYMENT_ID,
                BigInteger.ONE,
                1,
                2,
                ExecutionAttemptStatus.SUBMITTED
        );
        ineligibilityRegistry.markTemporarilyIneligible(
                DEPLOYMENT_ID,
                BigInteger.valueOf(3)
        );

        MatchProposal result = matchService.findNextProposal().orElseThrow();

        assertAll(
                () -> assertEquals(BigInteger.TWO, result.marketId()),
                () -> assertEquals(BigInteger.valueOf(5), result.buyOrderId()),
                () -> assertEquals(BigInteger.valueOf(4), result.sellOrderId())
        );
    }

    @Test
    void reservationQueryPreservesLargeIdentifiersAndDiscoveryIsReadOnly() {
        BigInteger marketId = new BigInteger("90071992547409930000000001");
        BigInteger buyOrderId = new BigInteger("90071992547409930000000002");
        BigInteger sellOrderId = new BigInteger("90071992547409930000000003");

        saveDeployment(DEPLOYMENT_ID, 1);
        saveMarket(DEPLOYMENT_ID, marketId);
        saveOpenOrder(
                DEPLOYMENT_ID,
                marketId,
                buyOrderId,
                OrderSide.BUY,
                1,
                BigInteger.valueOf(110),
                BigInteger.TEN,
                1
        );
        saveOpenOrder(
                DEPLOYMENT_ID,
                marketId,
                sellOrderId,
                OrderSide.SELL,
                2,
                BigInteger.valueOf(90),
                BigInteger.TEN,
                2
        );
        orderRepository.flush();
        insertAttempt(
                DEPLOYMENT_ID,
                marketId,
                buyOrderId,
                sellOrderId,
                ExecutionAttemptStatus.UNCERTAIN
        );

        List<ReservedOrderPair> reservations = executionAttemptRepository
                .findReservedOrderPairs(
                        DEPLOYMENT_ID,
                        ExecutionAttemptStatus.reservingStatuses()
                );

        assertAll(
                () -> assertEquals(1, reservations.size()),
                () -> assertEquals(buyOrderId, reservations.getFirst().getBuyOrderId()),
                () -> assertEquals(sellOrderId, reservations.getFirst().getSellOrderId()),
                () -> assertTrue(matchService.findNextProposal().isEmpty()),
                () -> assertEquals(2, orderRepository.count()),
                () -> assertEquals(1, executionAttemptRepository.count()),
                () -> assertEquals(
                        OrderStatus.OPEN,
                        orderRepository.findById(
                                new OrderEntityId(DEPLOYMENT_ID, buyOrderId)
                        ).orElseThrow().getStatus()
                )
        );
    }

    @Test
    void validCurrentProposalCreatesOneDurableReservationWithoutChangingOrders() {
        saveDeployment(DEPLOYMENT_ID, 1);
        saveMarket(DEPLOYMENT_ID, BigInteger.ONE);
        saveOpenOrder(DEPLOYMENT_ID, 1, 1, OrderSide.BUY, 1, 110, 10, 1);
        saveOpenOrder(DEPLOYMENT_ID, 1, 2, OrderSide.SELL, 2, 90, 10, 2);
        orderRepository.flush();
        MatchProposal proposal = matchService.findNextProposal().orElseThrow();
        EthereumAddress executor = address(999);
        Instant before = Instant.now();

        MatchReservationResult result = reservationService.reserveIfCurrent(
                proposal,
                executor
        );

        Instant after = Instant.now();
        var attempt = executionAttemptRepository.findAll().getFirst();
        assertAll(
                () -> assertEquals(MatchReservationResult.RESERVED, result),
                () -> assertEquals(1, executionAttemptRepository.count()),
                () -> assertEquals(DEPLOYMENT_ID, attempt.getDeploymentId()),
                () -> assertEquals(BigInteger.ONE, attempt.getMarketId()),
                () -> assertEquals(BigInteger.ONE, attempt.getBuyOrderId()),
                () -> assertEquals(BigInteger.TWO, attempt.getSellOrderId()),
                () -> assertEquals(BigInteger.TEN, attempt.getFillLots()),
                () -> assertEquals(executor, attempt.getExecutorAddress()),
                () -> assertEquals(
                        ExecutionAttemptStatus.RESERVED,
                        attempt.getStatus()
                ),
                () -> assertTrue(attempt.getAttemptId() != null),
                () -> assertTrue(!attempt.getCreatedAt().isBefore(before)),
                () -> assertTrue(!attempt.getCreatedAt().isAfter(after)),
                () -> assertEquals(attempt.getCreatedAt(), attempt.getUpdatedAt()),
                () -> assertTrue(attempt.getExecutorNonce() == null),
                () -> assertTrue(attempt.getTransactionHash() == null),
                () -> assertEquals(
                        OrderStatus.OPEN,
                        orderRepository.findById(
                                new OrderEntityId(DEPLOYMENT_ID, BigInteger.ONE)
                        ).orElseThrow().getStatus()
                ),
                () -> assertEquals(
                        BigInteger.TEN,
                        orderRepository.findById(
                                new OrderEntityId(DEPLOYMENT_ID, BigInteger.ONE)
                        ).orElseThrow().getRemainingLots()
                )
        );
    }

    @Test
    void existingUnresolvedAttemptBlocksAnotherReservation() {
        saveDeployment(DEPLOYMENT_ID, 1);
        saveMarket(DEPLOYMENT_ID, BigInteger.ONE);
        saveOpenOrder(DEPLOYMENT_ID, 1, 1, OrderSide.BUY, 1, 110, 10, 1);
        saveOpenOrder(DEPLOYMENT_ID, 1, 2, OrderSide.SELL, 2, 90, 10, 2);
        orderRepository.flush();
        MatchProposal proposal = matchService.findNextProposal().orElseThrow();
        insertAttempt(
                DEPLOYMENT_ID,
                BigInteger.ONE,
                1,
                2,
                ExecutionAttemptStatus.UNCERTAIN
        );

        assertEquals(
                MatchReservationResult.ALREADY_RESERVED,
                reservationService.reserveIfCurrent(proposal, address(999))
        );
        assertEquals(1, executionAttemptRepository.count());
    }

    @Test
    void changedTopProposalPreventsStaleReservation() {
        saveDeployment(DEPLOYMENT_ID, 1);
        saveMarket(DEPLOYMENT_ID, BigInteger.ONE);
        saveOpenOrder(DEPLOYMENT_ID, 1, 1, OrderSide.BUY, 1, 110, 10, 1);
        saveOpenOrder(DEPLOYMENT_ID, 1, 2, OrderSide.SELL, 2, 90, 10, 2);
        orderRepository.flush();
        MatchProposal staleProposal = matchService.findNextProposal().orElseThrow();
        saveOpenOrder(DEPLOYMENT_ID, 1, 3, OrderSide.BUY, 3, 120, 10, 3);
        orderRepository.flush();

        assertEquals(
                MatchReservationResult.STALE,
                reservationService.reserveIfCurrent(staleProposal, address(999))
        );
        assertEquals(0, executionAttemptRepository.count());
    }

    @Test
    void resolvedAttemptDoesNotBlockNewReservation() {
        saveDeployment(DEPLOYMENT_ID, 1);
        saveMarket(DEPLOYMENT_ID, BigInteger.ONE);
        saveOpenOrder(DEPLOYMENT_ID, 1, 1, OrderSide.BUY, 1, 110, 10, 1);
        saveOpenOrder(DEPLOYMENT_ID, 1, 2, OrderSide.SELL, 2, 90, 10, 2);
        orderRepository.flush();
        MatchProposal proposal = matchService.findNextProposal().orElseThrow();
        insertAttempt(
                DEPLOYMENT_ID,
                BigInteger.ONE,
                1,
                2,
                ExecutionAttemptStatus.ABANDONED
        );

        assertEquals(
                MatchReservationResult.RESERVED,
                reservationService.reserveIfCurrent(proposal, address(999))
        );
        assertEquals(2, executionAttemptRepository.count());
    }

    private void clearData() {
        ineligibilityRegistry.clear();
        executionAttemptRepository.deleteAll();
        orderRepository.deleteAll();
        marketRepository.deleteAll();
        deploymentRepository.deleteAll();
    }

    private void insertAttempt(
            String deploymentId,
            BigInteger marketId,
            long buyOrderId,
            long sellOrderId,
            ExecutionAttemptStatus status
    ) {
        insertAttempt(
                deploymentId,
                marketId,
                BigInteger.valueOf(buyOrderId),
                BigInteger.valueOf(sellOrderId),
                status
        );
    }

    private void insertAttempt(
            String deploymentId,
            BigInteger marketId,
            BigInteger buyOrderId,
            BigInteger sellOrderId,
            ExecutionAttemptStatus status
    ) {
        boolean hasTransactionIdentity = status != ExecutionAttemptStatus.RESERVED
                && status != ExecutionAttemptStatus.ABANDONED;
        Timestamp resolvedAt = status.isTerminal()
                ? Timestamp.from(START)
                : null;

        jdbcTemplate.update(
                """
                INSERT INTO execution_attempts (
                    attempt_id,
                    deployment_id,
                    market_id,
                    buy_order_id,
                    sell_order_id,
                    fill_lots,
                    execution_price_quote_atoms_per_lot,
                    executor_address,
                    executor_nonce,
                    transaction_hash,
                    signed_transaction_hex,
                    status,
                    created_at,
                    updated_at,
                    resolved_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                deploymentId,
                marketId,
                buyOrderId,
                sellOrderId,
                BigInteger.ONE,
                BigInteger.TEN,
                address(999).value(),
                hasTransactionIdentity ? buyOrderId : null,
                hasTransactionIdentity
                        ? hash(buyOrderId.add(sellOrderId)).value()
                        : null,
                hasTransactionIdentity ? "0x01" : null,
                status.name(),
                Timestamp.from(START),
                Timestamp.from(START),
                resolvedAt
        );
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

    private void saveMarket(String deploymentId, BigInteger marketId) {
        marketRepository.saveAndFlush(new MarketEntity(
                new MarketEntityId(deploymentId, marketId),
                address(100),
                address(101),
                "TKA",
                "TKB",
                18,
                18,
                BigInteger.valueOf(1_000_000),
                true
        ));
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
        saveOpenOrder(
                deploymentId,
                BigInteger.valueOf(marketId),
                BigInteger.valueOf(orderId),
                side,
                ownerSeed,
                BigInteger.valueOf(price),
                BigInteger.valueOf(remaining),
                block
        );
    }

    private void saveOpenOrder(
            String deploymentId,
            BigInteger marketId,
            BigInteger orderId,
            OrderSide side,
            long ownerSeed,
            BigInteger price,
            BigInteger remaining,
            long block
    ) {
        orderRepository.save(new OrderEntity(
                new OrderEntityId(deploymentId, orderId),
                marketId,
                address(ownerSeed),
                side,
                price,
                remaining,
                remaining,
                OrderStatus.OPEN,
                BigInteger.valueOf(block),
                BigInteger.ZERO,
                BigInteger.ZERO,
                hash(30_000 + block),
                hash(40_000 + block),
                START.plusSeconds(block)
        ));
    }

    private static EthereumAddress address(long value) {
        return new EthereumAddress("0x" + String.format("%040x", value));
    }

    private static Hash256 hash(long value) {
        return hash(BigInteger.valueOf(value));
    }

    private static Hash256 hash(BigInteger value) {
        return new Hash256("0x" + String.format("%064x", value));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        MutableClock matcherTestClock() {
            return new MutableClock(START);
        }

        @Bean
        @Primary
        TemporaryOrderIneligibilityRegistry matcherTestIneligibilityRegistry(
                DexProperties properties,
                MutableClock clock
        ) {
            return new TemporaryOrderIneligibilityRegistry(
                    properties.matcher().ineligibilityDuration(),
                    clock
            );
        }
    }

    static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void reset() {
            instant = START;
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
