package com.dex.backend.persistence.execution;

import com.dex.backend.TestcontainersConfiguration;
import com.dex.backend.blockchain.execution.PreparedSettlement;
import com.dex.backend.blockchain.execution.SettlementRequest;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.matcher.MatchProposal;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ExecutionAttemptLifecycleServiceIntegrationTest {

    private static final String DEPLOYMENT_ID = "integration-test";
    private static final String OTHER_DEPLOYMENT_ID = "other-deployment";
    private static final BigInteger MARKET_ID = BigInteger.ONE;
    private static final BigInteger BUY_ORDER_ID = BigInteger.TEN;
    private static final BigInteger SELL_ORDER_ID = BigInteger.valueOf(20);
    private static final BigInteger FILL_LOTS = BigInteger.valueOf(5);
    private static final EthereumAddress EXECUTOR = address(999);

    @Autowired
    private ExecutionAttemptLifecycleService lifecycleService;

    @Autowired
    private ExecutionAttemptRepository attemptRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private MarketRepository marketRepository;

    @Autowired
    private ChainDeploymentRepository deploymentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearDatabase() {
        attemptRepository.deleteAll();
        orderRepository.deleteAll();
        marketRepository.deleteAll();
        deploymentRepository.deleteAll();
    }

    @Test
    void persistsCompleteLifecycleAndLeavesOrderProjectionsUnchanged() {
        UUID attemptId = saveReservedFixture(DEPLOYMENT_ID);
        BigInteger nonce = BigInteger.ONE.shiftLeft(220);
        PreparedSettlement prepared = prepared(DEPLOYMENT_ID, nonce);

        lifecycleService.recordSigned(attemptId, prepared);
        lifecycleService.recordUncertain(
                attemptId,
                "BROADCAST_TIMEOUT",
                "The node did not return a broadcast result"
        );
        lifecycleService.recordSubmitted(attemptId);
        lifecycleService.recordConfirmed(attemptId);

        ExecutionAttemptEntity stored = attemptRepository.findById(attemptId)
                .orElseThrow();
        OrderEntity buy = orderRepository.findById(
                new OrderEntityId(DEPLOYMENT_ID, BUY_ORDER_ID)
        ).orElseThrow();
        OrderEntity sell = orderRepository.findById(
                new OrderEntityId(DEPLOYMENT_ID, SELL_ORDER_ID)
        ).orElseThrow();

        assertAll(
                () -> assertEquals(
                        ExecutionAttemptStatus.CONFIRMED,
                        stored.getStatus()
                ),
                () -> assertEquals(nonce, stored.getExecutorNonce()),
                () -> assertEquals(
                        prepared.transactionHash(),
                        stored.getTransactionHash()
                ),
                () -> assertEquals(
                        prepared,
                        stored.toPreparedSettlement()
                ),
                () -> assertEquals(
                        BigInteger.valueOf(100),
                        stored.getExecutionPriceQuoteAtomsPerLot()
                ),
                () -> assertNotNull(stored.getSubmittedAt()),
                () -> assertNotNull(stored.getResolvedAt()),
                () -> assertNull(stored.getErrorCode()),
                () -> assertNull(stored.getErrorMessage()),
                () -> assertFalse(hasUnresolved(DEPLOYMENT_ID)),
                () -> assertEquals(OrderStatus.OPEN, buy.getStatus()),
                () -> assertEquals(BigInteger.TEN, buy.getRemainingLots()),
                () -> assertEquals(OrderStatus.OPEN, sell.getStatus()),
                () -> assertEquals(BigInteger.TEN, sell.getRemainingLots())
        );
    }

    @Test
    void uncertainAttemptContinuesToReserveBothOrders() {
        UUID attemptId = saveReservedFixture(DEPLOYMENT_ID);
        lifecycleService.recordSigned(
                attemptId,
                prepared(DEPLOYMENT_ID, BigInteger.valueOf(7))
        );
        lifecycleService.recordUncertain(
                attemptId,
                "LOOKUP_FAILED",
                "Transaction lookup is temporarily unavailable"
        );

        ExecutionAttemptEntity stored = attemptRepository.findById(attemptId)
                .orElseThrow();
        var pairs = attemptRepository.findReservedOrderPairs(
                DEPLOYMENT_ID,
                ExecutionAttemptStatus.reservingStatuses()
        );

        assertAll(
                () -> assertEquals(
                        ExecutionAttemptStatus.UNCERTAIN,
                        stored.getStatus()
                ),
                () -> assertTrue(hasUnresolved(DEPLOYMENT_ID)),
                () -> assertEquals(1, pairs.size()),
                () -> assertEquals(
                        BUY_ORDER_ID,
                        pairs.getFirst().getBuyOrderId()
                ),
                () -> assertEquals(
                        SELL_ORDER_ID,
                        pairs.getFirst().getSellOrderId()
                )
        );
    }

    @Test
    void revertedAndAbandonedAttemptsReleaseReservations() {
        UUID revertedId = saveReservedFixture(DEPLOYMENT_ID);
        lifecycleService.recordSigned(
                revertedId,
                prepared(DEPLOYMENT_ID, BigInteger.ZERO)
        );
        lifecycleService.recordSubmitted(revertedId);
        lifecycleService.recordReverted(
                revertedId,
                "Contract rejected stale order"
        );

        assertFalse(hasUnresolved(DEPLOYMENT_ID));
        assertEquals(
                "Contract rejected stale order",
                attemptRepository.findById(revertedId)
                        .orElseThrow()
                        .getRevertReason()
        );

        attemptRepository.deleteById(revertedId);
        UUID abandonedId = saveAttemptForExistingProjection(DEPLOYMENT_ID);
        lifecycleService.abandon(
                abandonedId,
                "PRE_SIGN_VALIDATION_FAILED",
                "Reservation is no longer safe to sign"
        );

        ExecutionAttemptEntity abandoned = attemptRepository
                .findById(abandonedId)
                .orElseThrow();
        assertAll(
                () -> assertEquals(
                        ExecutionAttemptStatus.ABANDONED,
                        abandoned.getStatus()
                ),
                () -> assertNotNull(abandoned.getResolvedAt()),
                () -> assertFalse(hasUnresolved(DEPLOYMENT_ID))
        );
    }

    @Test
    void configuredDeploymentCannotMutateAnotherDeploymentAttempt() {
        UUID otherAttemptId = saveReservedFixture(OTHER_DEPLOYMENT_ID);

        assertThrows(
                ExecutionAttemptTransitionException.class,
                () -> lifecycleService.abandon(
                        otherAttemptId,
                        "WRONG_DEPLOYMENT",
                        "Must remain isolated"
                )
        );

        assertEquals(
                ExecutionAttemptStatus.RESERVED,
                attemptRepository.findById(otherAttemptId)
                        .orElseThrow()
                        .getStatus()
        );
    }

    @Test
    void invalidTransitionRollsBackWithoutLosingSignedIdentity() {
        UUID attemptId = saveReservedFixture(DEPLOYMENT_ID);
        PreparedSettlement prepared = prepared(
                DEPLOYMENT_ID,
                BigInteger.valueOf(42)
        );
        lifecycleService.recordSigned(attemptId, prepared);

        assertThrows(
                ExecutionAttemptTransitionException.class,
                () -> lifecycleService.abandon(
                        attemptId,
                        "UNSAFE_ABANDONMENT",
                        "A signed transaction must stay reserved"
                )
        );

        ExecutionAttemptEntity stored = attemptRepository.findById(attemptId)
                .orElseThrow();
        assertAll(
                () -> assertEquals(
                        ExecutionAttemptStatus.SIGNED,
                        stored.getStatus()
                ),
                () -> assertEquals(
                        prepared.nonce(),
                        stored.getExecutorNonce()
                ),
                () -> assertEquals(
                        prepared.transactionHash(),
                        stored.getTransactionHash()
                ),
                () -> assertNull(stored.getResolvedAt()),
                () -> assertTrue(hasUnresolved(DEPLOYMENT_ID))
        );
    }

    @Test
    void databaseRejectsIncompleteOrMalformedSignedPayloads() {
        UUID attemptId = saveReservedFixture(DEPLOYMENT_ID);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                        UPDATE execution_attempts
                        SET status = 'SIGNED',
                            executor_nonce = 1,
                            transaction_hash = ?,
                            signed_transaction_hex = '0x0'
                        WHERE attempt_id = ?
                        """,
                        hash(77).value(),
                        attemptId
                )
        );

        assertEquals(
                ExecutionAttemptStatus.RESERVED,
                attemptRepository.findById(attemptId).orElseThrow().getStatus()
        );
    }

    @Test
    void databasePreservesNonceAndTransactionHashUniqueness() {
        UUID firstAttemptId = saveReservedFixture(DEPLOYMENT_ID);
        PreparedSettlement prepared = prepared(
                DEPLOYMENT_ID,
                BigInteger.ONE.shiftLeft(220)
        );
        lifecycleService.recordSigned(firstAttemptId, prepared);

        UUID secondAttemptId = saveAttemptForExistingProjection(DEPLOYMENT_ID);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> lifecycleService.recordSigned(
                        secondAttemptId,
                        prepared
                )
        );

        assertAll(
                () -> assertEquals(
                        ExecutionAttemptStatus.SIGNED,
                        attemptRepository.findById(firstAttemptId)
                                .orElseThrow()
                                .getStatus()
                ),
                () -> assertEquals(
                        ExecutionAttemptStatus.RESERVED,
                        attemptRepository.findById(secondAttemptId)
                                .orElseThrow()
                                .getStatus()
                )
        );
    }

    private UUID saveReservedFixture(String deploymentId) {
        saveDeployment(deploymentId);
        saveMarket(deploymentId);
        saveOrder(deploymentId, BUY_ORDER_ID, OrderSide.BUY, 110, 1);
        saveOrder(deploymentId, SELL_ORDER_ID, OrderSide.SELL, 90, 2);
        orderRepository.flush();
        return saveAttemptForExistingProjection(deploymentId);
    }

    private UUID saveAttemptForExistingProjection(String deploymentId) {
        UUID attemptId = UUID.randomUUID();
        attemptRepository.saveAndFlush(ExecutionAttemptEntity.reserved(
                attemptId,
                proposal(deploymentId),
                EXECUTOR,
                Instant.now().minusSeconds(1)
        ));
        return attemptId;
    }

    private void saveDeployment(String deploymentId) {
        deploymentRepository.saveAndFlush(new ChainDeploymentEntity(
                deploymentId,
                BlockchainMode.MOCK,
                BigInteger.valueOf(31_337),
                address(500),
                address(500),
                BigInteger.ZERO,
                hash(0)
        ));
    }

    private void saveMarket(String deploymentId) {
        marketRepository.saveAndFlush(new MarketEntity(
                new MarketEntityId(deploymentId, MARKET_ID),
                address(100),
                address(200),
                "TKA",
                "TKB",
                18,
                18,
                BigInteger.ONE,
                true
        ));
    }

    private void saveOrder(
            String deploymentId,
            BigInteger orderId,
            OrderSide side,
            long price,
            long position
    ) {
        orderRepository.save(new OrderEntity(
                new OrderEntityId(deploymentId, orderId),
                MARKET_ID,
                address(orderId.longValueExact()),
                side,
                BigInteger.valueOf(price),
                BigInteger.TEN,
                BigInteger.TEN,
                OrderStatus.OPEN,
                BigInteger.valueOf(position),
                BigInteger.ZERO,
                BigInteger.ZERO,
                hash(position + 100),
                hash(position + 200),
                Instant.parse("2026-01-01T00:00:00Z")
        ));
    }

    private boolean hasUnresolved(String deploymentId) {
        return attemptRepository.existsUnresolved(
                deploymentId,
                ExecutionAttemptStatus.reservingStatuses()
        );
    }

    private static MatchProposal proposal(String deploymentId) {
        return new MatchProposal(
                deploymentId,
                MARKET_ID,
                BUY_ORDER_ID,
                SELL_ORDER_ID,
                FILL_LOTS,
                BigInteger.valueOf(100)
        );
    }

    private static PreparedSettlement prepared(
            String deploymentId,
            BigInteger nonce
    ) {
        MatchProposal proposal = proposal(deploymentId);
        return new PreparedSettlement(
                new SettlementRequest(
                        proposal.deploymentId(),
                        proposal.marketId(),
                        proposal.buyOrderId(),
                        proposal.sellOrderId(),
                        proposal.fillLots(),
                        proposal.executionPriceQuoteAtomsPerLot()
                ),
                EXECUTOR,
                nonce,
                hash(nonce.add(BigInteger.ONE)),
                "0x01"
        );
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
}
